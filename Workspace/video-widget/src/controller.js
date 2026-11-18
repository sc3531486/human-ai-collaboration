/**
 * 视频控件命令 API —— ★ 业务侧唯一入口（契约 §2.9.3）。
 *
 * ```
 * video.open({ mode, layout });          // 挂载控件
 * video.close();                         // 卸载并释放
 * video.connect({ peerId, processId });  // 建链（坐席端接听后调用）
 * video.hangup();
 * video.takeover('av' | 'audio');        // 接管模式，服务端下发为准
 * video.toggle('audio' | 'video');       // 本地静音 / 暂停画面
 * video.capture('remote' | 'local');     // ★ 抓帧，返回 Blob
 * video.record.start({ processId, bizNo, nodeName });
 * video.record.seal();                   // ★ 返回封存回执，含 fileSize
 * video.on('state', cb);
 * video.getState();
 * ```
 *
 * ★ 内核纯 JS：**不 import vue、不碰 DOM、不持有 MediaStream**（流全部经 surface 注入/读取）。
 *   因此本文件可以直接在 node 里跑状态机单测。
 *
 * ★ 业务侧红线：
 *   1. 只 import 本模块，**禁止** import `@/core/media/**`、禁止出现 `srcObject` / `MediaStream`。
 *   2. 唯一强依赖是 `capture()` 与 `record.seal()` 的返回值。
 *   3. `seal()` 回执 `fileSize === 0` 时**不得宣告存证成功**（§3.4），必须如实提示。
 *   4. 除这两点外不得假设视频存在——无视频时业务必须仍能办理到底。
 */

import * as layout from './layout.js';
import { SegmentRecorder, ChunkQueue } from './media/recorder.js';
import { TripleScreenCompositor, capture as captureFrame } from './media/capture.js';
import { LocalMedia } from './media/local.js';
import { NullSurface } from './media/surface.js';
import { createTransport } from './transport/registry.js';
import { TransportState, TransportEvent } from './transport/MediaTransport.js';

export const MODE = Object.freeze({ OVERLAY: 'overlay', DOCK: 'dock' });
export const TAKEOVER = Object.freeze({ AV: 'av', AUDIO: 'audio', RELEASED: 'released' });
export const CONNECTION = Object.freeze({
    IDLE: 'idle', CONNECTING: 'connecting', CONNECTED: 'connected', ENDED: 'ended', ERROR: 'error'
});

/**
 * 收集释放 / 清理路径上的失败：清理必须做完整（§8.4-5 停全部 track、§3.4 封存必达），
 * 但失败**不许静默吞掉**——先记账，由发起清理的命令统一上报（state.error + error 事件 + 抛错）。
 * 同步抛错与 Promise 拒绝都会被收进 failures，调用方可统一 await。
 */
function collectFailures(failures, label, run) {
    try {
        const result = run();
        return Promise.resolve(result).then(
            () => undefined,
            error => { failures.push({ label, error }); return undefined; }
        );
    } catch (error) {
        failures.push({ label, error });
        return Promise.resolve(undefined);
    }
}

/** 把收集到的失败拼成人话（环节：原因；环节：原因）。 */
function summarizeFailures(failures) {
    return failures.map(item => `${item.label}：${item.error?.message ?? item.error}`).join('；');
}

/**
 * 创建控件实例。默认导出的是共享单例（业务侧通常只需要一个），
 * 但**工厂可复用**——单测与将来"一屏多路"都要能各建各的。
 *
 * @param {{surface?:object, transport?:object, media?:object, queue?:object,
 *          upload?:Function, transportName?:string, transportOptions?:object,
 *          storage?:object, onState?:Function}} options
 */
export function createVideoController(options = {}) {
    let surface = options.surface ?? new NullSurface();
    const media = options.media ?? new LocalMedia(options.mediaOptions || {});
    const listeners = new Map();
    const timers = new Set();

    let transport = options.transport ?? null;
    const remoteStreams = new Map();
    let participants = [];
    let localPeerId = null;
    // 多人房间中由坐席/客户点击小窗选择的主画面；为空时使用角色优先级。
    let primaryPeerId = null;
    let recorder = null;
    let compositor = null;
    let compositeStream = null;
    let unsubscribes = [];
    /** 最近一次远端流：仅用于换承载面时迁移，**不进 state**（state 快照不得携带 MediaStream）。 */
    let remoteStream = null;

    const state = {
        mounted: false,
        mode: MODE.OVERLAY,
        layout: { ...layout.DEFAULT_LAYOUT },
        pixel: null,
        draggable: false,
        connection: CONNECTION.IDLE,
        call: false,
        recording: false,
        takeover: TAKEOVER.AV,
        audioEnabled: true,
        videoEnabled: true,
        hasRemote: false,
        hasLocal: false,
        hasDesktop: false,
        usingMockStream: false,
        recordingId: null,
        peerId: null,
        processId: null,
        bizNo: null,
        error: null
    };

    // ---------- 事件 ----------

    function on(event, handler) {
        if (typeof handler !== 'function') return () => {};
        if (!listeners.has(event)) listeners.set(event, new Set());
        listeners.get(event).add(handler);
        return () => off(event, handler);
    }

    function off(event, handler) {
        listeners.get(event)?.delete(handler);
    }

    function emit(event, payload) {
        const handlers = listeners.get(event);
        if (!handlers) return;
        for (const handler of [...handlers]) {
            try { handler(payload); }
            catch (error) { console.error(`[video] ${event} 订阅者异常`, error); }
        }
    }

    function set(patch = {}) {
        let changed = false;
        Object.entries(patch).forEach(([key, value]) => {
            if (state[key] !== value) { state[key] = value; changed = true; }
        });
        if (changed) emit('state', { ...state });
        return changed;
    }

    const getState = () => ({ ...state });

    // ---------- 传输 ----------

    function ensureTransport(localPeerId) {
        if (transport) return transport;
        transport = createTransport(options.transportName, {
            role: 'agent',
            // transport 的 peerId 是本地注册标识；connect() 参数的 peerId 才是对端标识。
            // 两者混用会让双方抢占同一个 PeerJS id，表现为“已接听但没有远端画面”。
            peerId: localPeerId ?? options.transportOptions?.peerId ?? null,
            ...(options.transportOptions || {})
        });
        wire(transport);
        return transport;
    }

    /**
     * 换传输实现：业务侧只传**逻辑名**（'p2p' / 'vendor'），
     * 不出现厂商字样；真正登记在 `transport/registry.js`。
     */
    function useTransport(name, transportOptions = {}) {
        const previous = transport;
        if (previous) {
            const failures = [];
            unsubscribes.forEach(fn => collectFailures(failures, '取消旧传输事件订阅', fn));
            unsubscribes = [];
            // 换实现这一步必须完成（旧传输已不再被接线），但释放失败不允许被吞：
            // 换完之后经 error 事件如实上报，便于宿主监控可能残留的连接。
            collectFailures(failures, '释放旧传输', () => previous.destroy?.()).then(() => {
                if (failures.length) {
                    emit('error', {
                        error: new Error(`切换传输实现时旧传输释放不完整：${summarizeFailures(failures)}`),
                        fatal: false,
                        phase: 'use-transport'
                    });
                }
            });
        }
        transport = createTransport(name, { role: 'agent', ...transportOptions });
        wire(transport);
        return transport;
    }

    function wire(instance) {
        unsubscribes = [
            instance.on(TransportEvent.STATE, ({ state: next }) => {
                const mapped = next === TransportState.CONNECTED ? CONNECTION.CONNECTED
                    : next === TransportState.CONNECTING || next === TransportState.RECONNECTING ? CONNECTION.CONNECTING
                        : next === TransportState.ERROR ? CONNECTION.ERROR
                            : next === TransportState.CLOSED || next === TransportState.DISCONNECTED ? CONNECTION.ENDED
                                : CONNECTION.IDLE;
                set({ connection: mapped, error: next === TransportState.ERROR ? state.error : null });
            }),
            instance.on(TransportEvent.LOCAL, ({ stream }) => {
                surface.attach('local', stream);
                set({ hasLocal: Boolean(stream) });
            }),
            instance.on(TransportEvent.REMOTE, ({ stream, peerId }) => {
                remoteStreams.set(peerId || state.peerId || 'remote', stream);
                media.addMixedStream?.(stream);
                renderRemotes();
            }),
            instance.on(TransportEvent.PEER_LEFT, ({peerId} = {}) => {
                if (peerId) { media.removeMixedStream?.(remoteStreams.get(peerId));remoteStreams.delete(peerId); }
                else remoteStreams.clear();
                renderRemotes();
            }),
            instance.on(TransportEvent.ERROR, ({ error, fatal }) => {
                // 对端尚未登录时 PeerJS 会给出 peer-unavailable。坐席已成功受理业务，
                // 此时应保留“等待客户接入”的舞台，等客户渠道端上线后回连；不能把
                // 这类可恢复状态渲染成红色的“接听失败”。真正无法建链的致命错误才置错。
                // ★ 但非致命错误也**不再是静默的**：一律广播 error 事件（不置红 UI），
                //   交给宿主的监控/上报通道，避免链路异常"看起来一切正常"。
                const detail = error instanceof Error
                    ? error
                    : new Error(error?.message ?? String(error));
                if (fatal) set({ error: detail.message, connection: CONNECTION.ERROR });
                emit('error', { error: detail, fatal: Boolean(fatal), phase: 'transport' });
            })
        ];
    }

    function orderedRemoteEntries() {
        const localRole = participants.find(member => member.peerId === localPeerId)?.role;
        const preferredRole = localRole === 'CUSTOMER' ? 'AGENT' : 'CUSTOMER';
        const entries = [...remoteStreams.entries()];
        entries.sort(([left], [right]) => {
            if (left === primaryPeerId) return -1;
            if (right === primaryPeerId) return 1;
            return Number(participants.find(p => p.peerId === right)?.role === preferredRole)
                - Number(participants.find(p => p.peerId === left)?.role === preferredRole);
        });
        return entries;
    }

    function renderRemotes() {
        const entries = orderedRemoteEntries();
        if (primaryPeerId && !remoteStreams.has(primaryPeerId)) primaryPeerId = null;
        remoteStream = entries[0]?.[1] || null;
        surface.attach('remote', remoteStream);
        surface.attach('secondary', entries[1]?.[1] || null);
        set({ hasRemote: Boolean(remoteStream), hasSecondaryRemote: Boolean(entries[1]?.[1]), call: entries.length > 0, participants: [...participants] });
    }

    /** 将当前小窗切换为主画面。三人房间中各端均可在两路远端画面间切换。 */
    function swapRemote() {
        const entries = orderedRemoteEntries();
        if (entries.length < 2) return false;
        primaryPeerId = entries[1][0];
        renderRemotes();
        return true;
    }

    async function updateParticipants(next = []) {
        if (!Array.isArray(next) || next.some(member => !member?.peerId || !member.role)) throw new Error('房间成员信息不完整');
        const instance = ensureTransport(localPeerId);
        const ids = new Set(next.map(member => member.peerId));
        for (const old of participants) if (!ids.has(old.peerId)) {
            instance.denyFrom?.(old.peerId);
            await instance.hangup?.(old.peerId);
            remoteStreams.delete(old.peerId);
        }
        participants = next.map(member => ({ ...member }));
        instance.allowFrom?.(participants.filter(member => member.peerId !== localPeerId).map(member => member.peerId));
        renderRemotes();
        return getState();
    }

    // ★ 构造时注入的 transport 同样要接线：否则 LOCAL/REMOTE/ERROR 事件永远没人订阅，
    //   远端流到达也不会切到"通话中"（默认走 ensureTransport/useTransport 的路径不受影响）。
    if (transport) wire(transport);

    // ---------- 生命周期 ----------

    /** 挂载控件。只管状态与布局，不申请任何媒体权限（同意告知书前不得申请）。 */
    async function open(config = {}) {
        // 宿主传参只校验、不纠偏：非法 mode/layout 直接报错，
        // 静默纠正（例如把 typo 落回 overlay）会让调用方的错误拖到出事才暴露。
        if (config.mode != null && config.mode !== MODE.OVERLAY && config.mode !== MODE.DOCK) {
            throw new Error(`不支持的窗口模式：${config.mode}（仅接受 overlay / dock）`);
        }
        if (config.layout != null && (typeof config.layout !== 'object' || Array.isArray(config.layout))) {
            throw new Error(`layout 必须是归一化布局对象，收到：${Array.isArray(config.layout) ? 'array' : typeof config.layout}`);
        }
        state.mode = config.mode === MODE.DOCK ? MODE.DOCK : MODE.OVERLAY;
        state.draggable = Boolean(config.draggable);
        state.layout = state.mode === MODE.DOCK
            ? { ...layout.DEFAULT_DOCK_LAYOUT, ...(config.layout || {}) }
            : { ...layout.DEFAULT_LAYOUT, ...(config.layout || {}) };
        state.pixel = config.pixel ?? null;
        set({
            mounted: true,
            mode: state.mode,
            layout: state.layout,
            pixel: state.pixel,
            draggable: state.draggable
        });
        return { ...state };
    }

    /** 卸载并释放：业务标签关闭、挂断或签出时调用。 */
    async function close() {
        const failures = await stopAll();
        if (failures.length) {
            // 释放路径红线：所有清理步骤都已执行完，失败逐条如实上报——
            // 既置 state.error，也广播 + 抛错，绝不"关掉了就当干净了"。
            const error = new Error(`控件已关闭，但清理未完全成功：${summarizeFailures(failures)}`);
            set({
                mounted: false, hasRemote: false, hasLocal: false, hasDesktop: false,
                connection: CONNECTION.IDLE, call: false, recording: false, error: error.message
            });
            emit('error', { error, fatal: true, phase: 'close' });
            throw error;
        }
        set({
            mounted: false, hasRemote: false, hasLocal: false, hasDesktop: false,
            connection: CONNECTION.IDLE, call: false, recording: false, error: null
        });
        return { ...state };
    }

    /**
     * 释放全部资源。清理失败**不中断**其余清理步骤（红线要求全部停掉），
     * 但每一条都被收集返回，由 close()/hangup() 统一置错 + 广播 + 抛出。
     *
     * @returns {Promise<{label:string, error:Error}[]>}
     */
    async function stopAll() {
        const failures = [];
        timers.forEach(timer => clearInterval(timer));
        timers.clear();
        if (recorder?.active) await collectFailures(failures, '放弃未封存的录制', () => recorder.abort());
        await collectFailures(failures, '停止画面合成', () => stopComposite());
        unsubscribes.forEach(fn => collectFailures(failures, '取消传输事件订阅', fn));
        unsubscribes = [];
        if (transport) {
            await collectFailures(failures, '释放传输', () => transport.destroy());
            transport = null;
        }
        remoteStream = null;
        primaryPeerId = null;
        remoteStreams.clear();participants = [];
        await collectFailures(failures, '释放媒体轨道', () => media.release());
        await collectFailures(failures, '清空承载面', () => surface.clear());
        return failures;
    }

    /**
     * 换画面承载面：视图层挂载/重建时把已有的流迁移到新 surface。
     *
     * 内核仍然不把 MediaStream 放进 `state`——迁移只用 media 上的本地/桌面流
     * 与闭包内的 `remoteStream`，业务侧依然只能经 capture()/seal() 拿到结果。
     *
     * @param {object} next 实现了 attach/detach 的承载面（通常是视图层的 DomSurface）
     */
    function useSurface(next) {
        if (!next || next === surface) return surface;
        surface = next;
        if (media.local) surface.attach('local', media.local);
        if (media.desktop) surface.attach('desktop', media.desktop);
        renderRemotes();
        return surface;
    }

    /**
     * 建链。**坐席端必须在接听的用户手势内调用**——桌面采集（`getDisplayMedia`）
     * 只有在手势的同步调用链里才被允许（§3.4）。
     *
     * @param {{peerId?:string, localPeerId?:string, processId?:string, localStream?:MediaStream,
     *          desktop?:boolean, mockStream?:boolean}} config
     */
    async function connect(config = {}) {
        localPeerId = config.localPeerId ?? localPeerId;
        // 不再替调用方补 open()：漏调 open 是调用方的顺序错误，静默补齐会把它掩盖过去。
        if (!state.mounted) {
            throw new Error('控件尚未初始化：请先调用 video.open({ mode, layout }) 再 connect()（open 只挂载状态，不申请媒体权限）');
        }
        state.processId = config.processId ?? state.processId;
        state.peerId = config.peerId ?? state.peerId;

        // 本地流：优先用宿主注入的（渠道方已有能力），否则自己在手势内申请。
        let stream = config.localStream ?? null;
        if (!stream) {
            try {
                stream = config.mockStream
                    ? await media.ensureStream(options.mockStreamFactory)
                    : await media.getUserMedia(config.constraints);
            } catch (error) {
                set({ connection: CONNECTION.ERROR, error: error.message });
                throw error;
            }
        } else {
            media.local = stream;
        }
        state.usingMockStream = Boolean(media.usingMockStream);
        if (state.usingMockStream) {
            // 模拟流是宿主显式配置（config.mockStream）的开关，开关本身不是静默兜底；
            // 但真实取流失败的原因必须如实广播——否则"演示画面"会掩盖设备/权限故障。
            emit('error', {
                error: new Error(`真实取流失败，已按宿主显式配置回退到模拟流（界面徽标显示"演示画面"）：${media.mockFallbackReason || '未记录具体原因'}`),
                fatal: false,
                phase: 'mock-stream'
            });
        }

        // 桌面采集也必须在同一手势内申请。
        if (config.desktop !== false) {
            try {
                await media.getDisplayMedia({
                    constraints: config.desktopConstraints,
                    onDesktopEnded: () => {
                        // 坐席点了"停止共享"：整段存证就此中断，必须立刻封存。
                        // 封存失败没有命令调用方在场 → 置 error 并广播，不再 catch 后静默丢弃。
                        if (recorder?.active) {
                            record.seal().catch(error => {
                                set({ error: `桌面共享中断，录制封存失败：${error?.message ?? error}` });
                                emit('error', { error, fatal: false, phase: 'desktop-ended-seal' });
                            });
                        }
                    }
                });
                surface.attach('desktop', media.desktop);
            } catch (error) {
                // 桌面采集失败不阻断通话（§2.9.3 无视频办到底）：只是这一通无法做三分屏存证，
                // 如实置 error 并广播，不阻断建链。
                set({ error: error.message });
                emit('error', { error, fatal: false, phase: 'desktop-capture' });
            }
        }

        surface.attach('local', stream);
        set({ hasLocal: Boolean(stream), hasDesktop: media.hasDesktop, usingMockStream: state.usingMockStream });

        const instance = ensureTransport(config.localPeerId);
        if (Array.isArray(config.participants)) await updateParticipants(config.participants);
        // P2P 来电必须先通过业务绑定的对端白名单。双方都配置白名单，既支持
        // 坐席外呼，也支持渠道端在重连后反向恢复会话。
        if (state.peerId && typeof instance.allowFrom === 'function') instance.allowFrom(state.peerId);
        // ★ W1（传输契约接线）：四方法契约以实现为准——connect() 由 controller 统一驱动，
        //   publish 前调用，第三方适配器按基类契约实现的 connect 不再是死代码。
        //   实现必须幂等（P2P 的 subscribe 自举会再调一次，见 P2PTransport.connect 的 guard）。
        //   由此注入式传输在这里把 connection 落到 connected（W2）：快照字段不再恒为 idle。
        await instance.connect({ peerId: state.peerId, processId: state.processId });
        await instance.publish(stream);

        if (config.dialAll === true) {
            for (const member of participants) if (member.peerId && member.peerId !== localPeerId) await instance.subscribe(member.peerId, {metadata:{processId:state.processId}});
        } else if (state.peerId && !config.incomingOnly) {
            await instance.subscribe(state.peerId, { metadata: { processId: state.processId } });
        } else {
            // incomingOnly 表示本端已由服务端信令通知加入房间，只接听坐席外呼。
            // 没有绑定远端客户时同样只保留本地见证。
            set({ call: false });
        }
        return { ...state };
    }

    /**
     * 仅注册本端信令标识，不挂载窗口、不申请摄像头/麦克风、也不发起外呼。
     * 渠道端在收到服务端 VIDEO_ROOM_READY 前调用它，确保坐席接听后可以找到客户。
     */
    async function prepare(config = {}) {
        localPeerId = config.localPeerId ?? localPeerId;
        if (!config.localPeerId) throw new Error('预注册视频端点需要 localPeerId');
        if (transport?.peerId && transport.peerId !== config.localPeerId) {
            // 新会话使用 media 分配的新用户，不能复用上一次失败接听留下的 Peer。
            await transport.destroy();
            unsubscribes.forEach(off => off());
            unsubscribes = [];
            transport = null;
        }
        state.processId = config.processId ?? state.processId;
        const instance = ensureTransport(config.localPeerId);
        if (config.peerId && typeof instance.allowFrom === 'function') instance.allowFrom(config.peerId);
        await instance.connect({ localPeerId: config.localPeerId, processId: state.processId });
        return { ...state };
    }

    async function hangup() {
        // 挂断的每一步都要执行到底（§3.4 封存必达 / §8.4-5 停全部 track），
        // 失败逐条收集：全部执行完后置 error + 广播 + 抛错，绝不 catch 后静默。
        const failures = [];
        await collectFailures(failures, '停止画面合成', () => stopComposite());
        if (recorder?.active) await collectFailures(failures, '封存进行中的录制', () => record.seal());
        await collectFailures(failures, '挂断传输', () => transport?.hangup());
        remoteStream = null;
        surface.detach('remote');
        // ★ 红线 §8.4-5：hangup 时停止全部 track（本地 + 桌面 + 注入），
        //   长通话不得留下仍在采集的僵尸轨道；下次 connect 重新申请即可。
        await collectFailures(failures, '释放媒体轨道', () => media.release());
        surface.detach('local');
        surface.detach('desktop');
        set({
            call: false, hasRemote: false, hasLocal: false, hasDesktop: false,
            usingMockStream: false, connection: CONNECTION.ENDED
        });
        if (failures.length) {
            const error = new Error(`挂断已执行，但以下环节失败：${summarizeFailures(failures)}`);
            set({ error: error.message });
            emit('error', { error, fatal: true, phase: 'hangup' });
            throw error;
        }
        return { ...state };
    }

    /**
     * 切换接管模式。
     * ★ 控件**不下业务判断**：只按服务端下发的 `takeover_mode` 渲染/执行。
     *   audio 模式下坐席视频不参与呈现，停掉视频轨省带宽；切回 av 再开回来。
     */
    async function takeover(mode) {
        if (!Object.values(TAKEOVER).includes(mode)) throw new Error(`不支持的接管模式：${mode}`);
        if (state.call) media.setVideoEnabled(mode === TAKEOVER.AV);
        set({ takeover: mode, videoEnabled: mode === TAKEOVER.AV ? state.videoEnabled : false });
        return mode;
    }

    /** 本地静音 / 暂停画面。无轨道时如实报错（先 connect 才有轨道），不再原样返回当前值。 */
    async function toggle(kind) {
        if (kind !== 'audio' && kind !== 'video') throw new Error(`不支持的开关：${kind}`);
        const next = media.toggle(kind);
        if (next === null || next === undefined) {
            throw new Error(`当前没有本地${kind === 'audio' ? '音频' : '视频'}轨道，无法切换：请先 connect() 取得本地流后再调用`);
        }
        set(kind === 'audio' ? { audioEnabled: next } : { videoEnabled: next });
        return next;
    }

    /**
     * 抓帧。**返回 Blob**（人工授权现场照片依赖）。
     * 未收到画面时返回 **null**——绝不伪造占位图冒充成功。
     */
    async function capture(source = 'remote') {
        if (source !== 'remote' && source !== 'local') throw new Error(`不支持的抓帧源：${source}`);
        const drawable = surface.drawable(source);
        return captureFrame(drawable, options.captureOptions || {});
    }

    // ---------- 录制 ----------

    let recordingUpload = options.upload ?? null;

    function ensureRecorder(uploadOverride) {
        if (typeof uploadOverride === 'function' && uploadOverride !== recordingUpload) {
            if (recorder?.active) throw new Error('录像进行中，不能切换上传通道');
            recordingUpload = uploadOverride;
            recorder = null;
        }
        if (recorder) return recorder;
        const queue = options.queue ?? new ChunkQueue({
            upload: recordingUpload ?? defaultUpload,
            indexedDB: options.indexedDB,
            Blob: options.Blob
        });
        recorder = new SegmentRecorder({
            queue,
            MediaRecorder: options.MediaRecorder,
            Blob: options.Blob,
            timeslice: options.timeslice
        });
        recorder.onError = error => set({ error: error.message });
        return recorder;
    }

    /** 独立控件不知道业务服务地址，必须由宿主注入录像分片上传能力。 */
    async function defaultUpload() {
        throw new Error('录像上传通道未配置');
    }

    /**
     * 三分屏合成：桌面 + 远端 + 本地。
     * ★ 合成是**存证要求**（已采集桌面时这一段必须是三分屏）：画布/captureStream/混音
     *   任何一路不可用都如实报错，**不再返回 null 让上层悄悄改录原始流**。
     *   确实只想录原始流时，由调用方显式传 `{ composite: false }`。
     */
    function startComposite() {
        stopComposite();
        const canvas = surface.createCanvas();
        if (!canvas) {
            throw new Error('三分屏合成不可用：承载面未提供画布（surface.createCanvas() 返回空），无法录制坐席桌面存证。请修复承载面，或显式以 { composite: false } 录制原始流');
        }
        try {
            compositor = new TripleScreenCompositor({
                canvas,
                setInterval: options.setInterval,
                clearInterval: options.clearInterval,
                timestamp: options.timestamp
            });
            compositor.setSource('desktop', media.desktopVideo ?? surface.source('desktop'));
            compositor.setSource('remote', surface.drawable('remote'));
            compositor.setSource('local', surface.drawable('local'));
            compositor.start();
            compositeStream = compositor.captureStream(options.compositeFrameRate ?? 10);
            if (!compositeStream) {
                throw new Error('三分屏合成不可用：画布不支持 captureStream()，无法录制坐席桌面存证。请修复承载面，或显式以 { composite: false } 录制原始流');
            }
            if (state.takeover !== TAKEOVER.AUDIO) {
                const sources = [media.local, ...remoteStreams.values()].filter(stream => stream?.getAudioTracks?.().length);
                const mixed = media.mixAudio([media.local, ...remoteStreams.values()]);
                if (sources.length && !mixed?.getAudioTracks?.().length) {
                    throw new Error('三分屏录制需要混入音频，但混音不可用（AudioContext 缺失或被拒绝）：无法录制含声音的存证。请修复后重试，或显式以 { composite: false } 录制原始流');
                }
                mixed?.getAudioTracks?.().forEach(track => compositeStream.addTrack(track));
            }
            return compositeStream;
        } catch (error) {
            // 起了一半的合成循环必须收干净，不留僵尸定时器；
            // 收尾再失败也不许盖掉原始错误——合并进原因一起抛。
            try { stopComposite(); }
            catch (cleanupError) { error.message = `${error.message}；合成收尾也失败：${cleanupError.message}`; }
            throw error;
        }
    }

    function stopComposite() {
        const failures = [];
        compositor?.stop();
        if (compositeStream) compositeStream.getTracks?.().forEach((track, index) => {
            collectFailures(failures, `停止合成轨道 #${index}`, () => track.stop());
        });
        compositor = null;
        compositeStream = null;
        if (failures.length) throw new Error(`停止画面合成失败：${summarizeFailures(failures)}`);
        return true;
    }

    const record = {
        /**
         * 开始一段录制。
         * @param {{processId?:string,bizNo?:string,nodeName?:string,segmentIndex?:number,
         *          handlerType?:string,handlerName?:string,composite?:boolean,upload?:Function}} meta
         */
        async start(meta = {}) {
            const instance = ensureRecorder(meta.upload);
            state.processId = meta.processId ?? state.processId;
            state.bizNo = meta.bizNo ?? state.bizNo;

            let stream;
            if (meta.composite !== false && media.hasDesktop) {
                // 已采集桌面 → 这一段必须是三分屏存证：合成起不来就如实失败，
                // 不再悄悄改录原始流（那会让存证缺画面却"成功"）。
                stream = startComposite();
            } else {
                // 无桌面，或调用方显式 { composite: false }：按原始流录制。
                // ★ 不再回退到 compositeStream：调用方明确说不合成时还给合成流，
                //   就是无视调用方选择的静默换路；无桌面时它也必为 null。
                stream = surface.source('remote') ?? media.local;
            }
            if (!stream) throw new Error('当前没有可录制的媒体流，请先连接音视频');

            const recordingId = await instance.start(stream, {
                ...meta,
                processId: state.processId,
                bizNo: state.bizNo,
                segmentIndex: meta.segmentIndex ?? 2,
                handlerType: meta.handlerType ?? 'HUMAN_AGENT',
                handlerName: meta.handlerName ?? options.agentName ?? '远程坐席'
            });
            set({ recording: true, recordingId });
            return { recordingId, processId: state.processId, bizNo: state.bizNo, nodeName: meta.nodeName ?? null };
        },

        /**
         * 封存：返回含 `fileSize` 的回执。
         * ★ `fileSize === 0` 时如实返回，**业务侧不得宣告存证成功**（§3.4）。
         * ★ 收尾（停合成 / 释放桌面 / 状态回写）放在 finally：封存报错也必须收干净；
         *   收尾自身的失败走 error 事件上报，既不吞掉、也不盖掉封存本身的回执或异常。
         */
        async seal() {
            const instance = recorder;
            if (!instance) return { success: false, fileSize: 0, message: '当前没有进行中的录制' };
            let receipt;
            try {
                receipt = await instance.seal();
            } finally {
                const failures = [];
                await collectFailures(failures, '停止画面合成', () => stopComposite());
                await collectFailures(failures, '释放坐席桌面', () => media.releaseDesktop());
                set({ recording: false, recordingId: null, hasDesktop: false });
                if (failures.length) {
                    emit('error', {
                        error: new Error(`录制封存收尾未完全成功：${summarizeFailures(failures)}`),
                        fatal: false,
                        phase: 'seal-cleanup'
                    });
                }
            }
            return receipt;
        },

        /** 页面重载后的补传：同一 recordingId 的待传分片重新排空。 */
        async resume() {
            const queue = options.queue ?? ensureRecorder().queue;
            const receipt = await queue.flush();
            const result = { ...(receipt || {}) };
            result.fileSize = Number(result.fileSize) || 0;
            // 与 seal 同一口径：0 字节的补传回执不得宣告成功（§3.4）。
            result.success = Boolean(result.success) && result.fileSize > 0;
            return result;
        },

        get active() { return Boolean(recorder?.active); }
    };

    // ---------- 布局（overlay / dock） ----------

    /** 按当前模式解析像素盒子；dock 下返回 `{ order, heightRatio, height }`。 */
    function resolveBox(viewport = {}) {
        return layout.resolve(state.mode, state.layout, viewport, state.pixel || {});
    }

    /** 拖拽落位：回写**归一化值**并持久化，不存像素。 */
    function moveTo(box, viewport) {
        if (state.mode === MODE.DOCK) return { ...state.layout };
        const normalized = layout.normalize(box, viewport);
        state.layout = { ...state.layout, ...normalized };
        layout.persistLayout(state.layout, { storage: options.storage });
        set({ layout: state.layout });
        return { ...state.layout };
    }

    function setLayout(next = {}) {
        if (next != null && (typeof next !== 'object' || Array.isArray(next))) {
            throw new Error(`layout 必须是归一化布局对象，收到：${Array.isArray(next) ? 'array' : typeof next}`);
        }
        state.layout = layout.mergeLayout(state.layout, next);
        layout.persistLayout(state.layout, { storage: options.storage });
        set({ layout: state.layout });
        return { ...state.layout };
    }

    function restoreLayout(fallback) {
        state.layout = layout.restoreLayout(fallback ?? state.layout, { storage: options.storage });
        set({ layout: state.layout });
        return { ...state.layout };
    }

    /**
     * 质量观测。规格：**永不抛错**（§6.4，统计失败不影响通话），
     * 但拿不到观测值时必须如实标注 `degraded`，不得返回一个"看起来正常"的空壳。
     */
    function getStats() {
        try {
            const stats = transport?.getStats?.();
            if (stats) return stats;
            return {
                state: state.connection,
                degraded: true,
                error: transport ? '当前传输实现未提供观测数据' : '传输尚未建立，观测数据暂缺'
            };
        } catch (error) {
            return { state: state.connection, degraded: true, error: error?.message ?? String(error) };
        }
    }

    /**
     * 供宿主注入外部已有流（渠道方 SDK 场景）。
     * ★ 不支持注入的传输实现如实报错——静默跳过会让 hasRemote/call 变 true
     *   却没有任何流真正进入链路，上层完全看不出问题。
     */
    function injectStream(stream, peerId = 'injected') {
        const instance = ensureTransport();
        if (typeof instance.injectStream !== 'function') {
            throw new Error(`当前传输实现（${instance.name}）不支持注入外部流：请先 controller.useTransport('injected')，或改由传输自行提供远端流`);
        }
        remoteStream = stream;
        surface.attach('remote', stream);
        instance.injectStream(stream, peerId);
        set({ hasRemote: true, call: true, peerId });
        return peerId;
    }

    return {
        open, close, prepare, connect, updateParticipants, swapRemote, hangup, takeover, toggle, capture, record, on, off, getState,
        resolveBox, moveTo, setLayout, restoreLayout, getStats, injectStream, useTransport, useSurface,
        get transport() { return transport; },
        get recorder() { return recorder; },
        get surface() { return surface; }
    };
}

/**
 * 共享单例。业务侧 `import { video } from '@/core/video/controller'` 即可，
 * 与 vendor 前的 stub 用法完全一致，替换时业务侧一行不改。
 */
export const video = createVideoController();

export { layout };

export default video;
