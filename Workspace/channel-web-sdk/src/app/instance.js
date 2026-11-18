/*
 * SDK 实例编排（契约 §1 / §6.1 / §3.6）：
 *   mount（建壳 + 装配）→ startBusiness（能力门禁 + 实时通道）→ destroy（全量拆卸）。
 * SDK 只拥有传入的容器，不接管宿主路由（§1）。
 */
import { configure as sdkConfigure, flow, evidence, logAction, resolveApiUrl, request } from '../core/sdk.js';
import { installBridge } from '../bridge/index.js';
import { probeCapabilities } from '../core/capabilities.js';
import { identity } from '../core/identity.js';
import { trace } from '../core/trace.js';
import { sync } from '../core/flow/sync.js';
import { stomp } from '../core/realtime/stomp.js';
import { customerSignal } from '../core/realtime/centrifugo.js';
import { configure as configureWidget, setCaptureFallback, video, teardown } from '../core/video-widget.js';
import { isHumanVideoNode, isWaitingForAgent } from '../core/flow/policy.js';
import { buildShell } from './shell.js';
import { createSpeech } from './speech.js';
import { createWaitTone } from './wait-tone.js';
import { createMedia } from './media.js';
import { createTakeover } from './takeover.js';
import { createChat } from './chat.js';
import { createNodeDialog } from './node-dialog.js';
import { createConsent } from './consent.js';
import { createVideoSession } from './video-session.js';
import { createRecording } from './recording.js';
import { loadAiConfig } from './ai-config.js';
import VbSteps from '../ui/VbSteps.js';
import { createEvaluation } from './evaluation.js';

let active = null;

/** 当前活动实例（供公共 API 的 startBusiness/destroy 便捷入口使用）。 */
export const getActive = () => active;

export function mount(element, config = {}) {
    if (active) throw new Error('渠道 SDK 已有实例在运行，请先调用 destroy() 再次挂载。');
    const container = typeof element === 'string' ? document.querySelector(element) : element;
    if (!container) throw new Error('mount(el): 未找到挂载容器。');

    sdkConfigure({ apiBase: config.apiBase });
    installBridge(config.bridge);
    identity.ensure(config);

    const refs = buildShell(container, {
        avatarUrl: config.avatarUrl,
        avatarStillUrl: config.avatarStillUrl,
        speakingAvatarUrl: config.speakingAvatarUrl,
        chatHome: config.chatHome,
    });
    let disposed = false;
    let currentProcessId = config.processId || '';
    let targetAgentPeerId = '';
    let activeHandlerRole = 'DIGITAL_HUMAN';
    let lastNodeName = '';         // 最近一次服务端节点名，录进 record.start meta 留痕
    let hasHandledClosingBroadcast = false;
    let peerPrepareTimer = null;
    let mediaPeerId = '';
    let roomInvited = false;
    let callId = null;
    let pendingServicePrompt = '';
    let allowCamera;
    let announcedIdentityAudit = '';
    const endedCalls = new Set();
    let latestState = null;
    const capabilities = probeCapabilities();

    const speech = createSpeech(refs, { getMode: () => takeover.mode });
    const waitTone = createWaitTone(refs);
    const media = createMedia(refs, { getConsent: () => consent.consented });

    configureWidget({
        widgetModule: config.widgetModule,
        widgetUrl: config.widgetUrl,
        widgetCssUrl: config.widgetCssUrl,
        recordingUpload: async (form) => {
            const response = await fetch(resolveApiUrl('/api/balance/record/stream-chunk'), { method: 'POST', body: form });
            const receipt = await response.json().catch(() => ({ success: false }));
            if (!response.ok || receipt.success === false) throw new Error(receipt.error || receipt.message || '录像分片待补传');
            return receipt;
        }
    });
    setCaptureFallback(() => media.captureFrame());

    // ---- 告知书同意（§3.6 红线）：同意前 session.engage 只挂起，不取任何媒体 ----
    const consent = createConsent(refs, {
        speech,
        waitTone,
        media,
        onAccepted: () => {
            session.resume();
            allowCamera?.();
            allowCamera = undefined;
            if (!pendingServicePrompt) return false;
            const prompt = pendingServicePrompt;
            pendingServicePrompt = '';
            void chat.quickFill(prompt);
            return true;
        },
    });

    // ---- video-widget 接入（§2.9.3 指令 API；会话状态机收敛在 video-session.js） ----
    const session = createVideoSession(refs, {
        consent,
        media,
        getPeerId: () => targetAgentPeerId,
        getLocalPeerId: () => mediaPeerId,
        getProcessId: () => currentProcessId,
        isDisposed: () => disposed,
    });

    // 提前注册渠道端 Peer 只是信令就绪，不打开视频窗口也不申请媒体权限。
    // 房间加入和媒体接入仍严格由服务端 VIDEO_ROOM_READY 指令驱动。
    // PeerJS 由渠道宿主以 defer 脚本提供；若 SDK 比该脚本先执行，短暂重试即可，
    // 不把一次加载顺序差异扩大成坐席端的 unavailable-id。
    let peerPrepareAttempts = 0;
    const prepareVideoEndpoint = async () => {
        if (disposed || peerPrepareAttempts >= 10) return;
        peerPrepareAttempts += 1;
        const ready = await session.prepare();
        if (!ready && !disposed) {
            peerPrepareTimer = setTimeout(prepareVideoEndpoint, 600);
        }
    };
    const mediaUserReady = request('/api/media/users', { role: 'CUSTOMER', subject: identity.ensure(config) }).then(user => {
        mediaPeerId = user.peerId;
        if (!mediaPeerId) throw new Error('媒体服务未分配客户用户');
        prepareVideoEndpoint();
        customerSignal.start({
            id: identity.ensure(config),
            granted: user,
            onVideoRoomReady: async (event) => {
                if (disposed || event.processId !== currentProcessId) return;
                roomInvited = true;
                callId = event.callId;
                refs.hangupBtn.hidden = false;
                refs.phone.classList.add('vb-human-connected');
                activeHandlerRole = 'HUMAN_AGENT';
                waitTone.stop();
                window.speechSynthesis?.cancel();
                if (event.agentPeerId) targetAgentPeerId = event.agentPeerId;
                await video.prepare({ localPeerId: mediaPeerId, peerId: targetAgentPeerId });
                if (Array.isArray(event.participants)) await video.updateParticipants(event.participants);
                if (!consent.consented) consent.show();
                await session.engage({ mode: 'dock', incomingOnly: true });
            },
            onChatMessage: event => {
                if (!disposed && event.processId === currentProcessId) chat.receiveMessage(event);
            },
            onNodeState: event => {
                if (!disposed && event.processId === currentProcessId) sync.acceptPush(event);
            },
            onCallEnded: event => onCallEnded(event),
            onParticipantsChanged: event => { if (event.callId === callId) return video.updateParticipants(event.participants); },
        });
        return user;
    });
    mediaUserReady.catch(error => console.error('[media-user] 客户媒体用户申请失败', error));


    // ---- 录像分段台账（fileSize===0 与回执缺失如实分开记账，收敛在 recording.js） ----
    const recording = createRecording({
        getProcessId: () => currentProcessId,
        getBizNo: () => chat.bizNo || '',
        getNodeName: () => lastNodeName,
        isDisposed: () => disposed,
    });

    const takeover = createTakeover(refs, {
        speech,
        engageWidget: opts => roomInvited ? session.engage({ ...opts, incomingOnly: true }) : false,
        releaseWidget: session.release,
        onNotice: (text) => chat.addMsg('system', text),
    });

    const chat = createChat(refs, {
        getConsent: () => consent.consented,
        showConsent: () => consent.show(),
        speech,
        primeAudio: () => waitTone.prime(),
        getChatMode: () => roomInvited ? 'HUMAN' : isWaitingForAgent(latestState, false) ? 'WAITING' : 'DIGITAL',
        getProcessId: () => currentProcessId,
        getPeerId: () => mediaPeerId,
        getCustomerId: () => identity.ensure(config),
        ready: async () => { await mediaUserReady; await customerSignal.ready(); },
        onProcess: async (pid) => {
            currentProcessId = pid;
            await sync.setProcess(pid);
        },
        onHold: () => sync.hold(),
        onResume: () => sync.resume(),
        onForm: async (state, payload) => {
            hasHandledClosingBroadcast = false;      // 新表单即新办结观察窗口（navis 同口径）
            await nodeDialog.showMobileForm(state, payload || {});
            sync.resume();
        },
        onSegmentStart: (index, handlerType) => recording.start(index, handlerType || 'DIGITAL_HUMAN'),
    });

    const nodeDialog = createNodeDialog(refs, {
        getPeerId: () => mediaPeerId,
        primeAudio: () => waitTone.prime(),
        authorizeCapture: () => {
            if (consent.consented) return Promise.resolve();
            return new Promise((resolve) => {
                allowCamera = resolve;
                consent.show();
            });
        },
        photoUpload: (state, fieldName, blob) => evidence.photo(state, fieldName, blob, { clientId: mediaPeerId }, trace.id()),
        captureFrame: () => video.capture('local'),
        submit: async (state, payload, action) => flow.submit(state, payload, { clientId: mediaPeerId }, action),
        onApplied: (latest) => applyState(latest),
        rematch: () => sync.poll().catch((error) => {
            console.error('[VB-Banking] 手动重试流程校准失败:', error);
        }),
    });

    /** 接收流程办结结果，通话仍在进行时继续保留媒体与录制。 */
    async function finishClosingBroadcast(index) {
        sync.hold();
        if (roomInvited) {
            VbSteps.close(refs.stepper);
            chat.addMsg('system', '业务已完成，您可以继续与坐席通话。');
            return;
        }
        chat.stopAgentChat();
        await recording.finish(index);

        // 封存结果为空文件或回执缺失时不得宣告存证成功（红线：如实上报）。
        recording.warnings().forEach((line) => chat.addMsg('system', line));

        try {
            const state = await flow.state(currentProcessId);
            if (state.processStatus !== 'COMPLETED') throw new Error('流程尚未完成');
        } catch (error) {
            hasHandledClosingBroadcast = false;
            sync.resume();
            chat.addMsg('system', '办理结果确认失败，请稍候重试：' + error.message);
            return;
        }

        VbSteps.close(refs.stepper);
        await media.release();
        chat.addMsg('system', '本次业务流程已完成，录制归档状态以服务端记录为准。');
    }

    /** 状态应用（源：applyMobileState 逐条移植）。 */
    async function applyState(data) {
        if (disposed || !data || data.processId !== currentProcessId) return;
        latestState = data;
        if (!roomInvited && !isHumanVideoNode(data) && data.takeover_mode === 'released') {
            roomInvited = false;
            refs.phone.classList.remove('vb-human-connected');
            activeHandlerRole = 'DIGITAL_HUMAN';
        }
        const failedIdentity = data.nodeId === 'verification_review' && data.vars?.identityPassed === false;
        const transferNotice = data.vars?.onlinePassed === false
            ? '联网核查未通过，正在为您转接人工坐席。'
            : '人脸比对未通过，正在为您转接人工坐席。';
        refs.waitTitle.textContent = failedIdentity ? transferNotice : '正在等待坐席接入';
        if (failedIdentity && data.vars.identityAuditId && announcedIdentityAudit !== data.vars.identityAuditId) {
            announcedIdentityAudit = data.vars.identityAuditId;
            chat.addMsg('ai', transferNotice);
        }
        // 等待音跟随人工排队与服务端入房指令，不等待表单加载或摄像头画面。
        waitTone.sync(data, roomInvited);
        try {
            await nodeDialog.showMobileForm(data);
        } catch (error) {
            console.error('打开业务表单失败', error);
            refs.dialog.hidden = false;
            refs.panel.hidden = false;
            refs.panel.innerHTML = '<article class="node-form-body"><h3>业务页面加载失败</h3><p>请点击下方按钮重新加载当前办理页面。</p><button class="node-primary" type="button">重新加载</button></article>';
            refs.panel.querySelector('button').onclick = () => sync.poll().catch((error) => {
                console.error('[VB-Banking] 重载当前办理页面失败:', error);
            });
        }
        VbSteps.render(refs.stepper, data);
        if (data.nodeName) lastNodeName = data.nodeName;
        if (data.agentPeerId) targetAgentPeerId = data.agentPeerId;
        // 人工视频/见证节点（判据单一事实源在 core/flow/policy.js，与等待音乐同口径）。
        // 数字人阶段不得根据残留 takeover_mode 自行打开摄像头或视频控件。
        if (isHumanVideoNode(data)) {
            activeHandlerRole = 'HUMAN_AGENT';
            if (data.takeover_mode) await takeover.apply(data.takeover_mode);
            // 告知书只在真正需要人工音视频时展示；其余数字人节点不取媒体权限。
            if (!consent.consented) consent.show();
            // 不在节点状态变更时自行拨号；仅等待 media-svc 发出的 VIDEO_ROOM_READY。
        } else if (!roomInvited && data.takeover_mode === 'released') {
            await takeover.apply('released');
        }

        if (data.processStatus === 'COMPLETED') {
            sync.hold();
            if (!roomInvited) {
                chat.stopAgentChat();
                await takeover.apply('released');
                await media.release();
            }
            if (!hasHandledClosingBroadcast) {
                hasHandledClosingBroadcast = true;
                await finishClosingBroadcast(recording.index);
            }
        }
    }

    // ---- 输入区与快捷问法 ----
    const evaluation = createEvaluation(refs, {
        customerId: () => identity.ensure(config),
        summary: () => ({
            completed: latestState?.processStatus === 'COMPLETED',
            name: latestState?.bizName || latestState?.businessName || latestState?.bizTypeName || '远程视频银行服务'
        }),
        onSaved: () => chat.addMsg('system', '感谢您的评价')
    });
    async function onCallEnded(event) {
        if (!event?.callId || event.callId !== callId || endedCalls.has(event.callId)) return;
        endedCalls.add(event.callId);
        roomInvited = false;refs.hangupBtn.hidden = true;
        waitTone.stop();
        await recording.finish(recording.index);
        session.release();
        await media.release();
        refs.phone.classList.remove('vb-human-connected');
        activeHandlerRole = 'DIGITAL_HUMAN';
        chat.addMsg('system', event.initiatorRole === 'AGENT' ? '坐席已挂断，本次视频通话已结束。' : '视频通话已结束。');
        await evaluation.show(event);
        customerSignal.stop();
    }
    refs.hangupBtn.onclick = async () => {
        refs.hangupBtn.disabled = true;
        try { const event = await request(`/api/calls/${encodeURIComponent(callId)}/hangup`, { actorId: identity.ensure(config), actorRole: 'CUSTOMER' });await onCallEnded(event); }
        catch (error) { chat.addMsg('system', error.message); }
        finally { refs.hangupBtn.disabled = false; }
    };
    const onQuick = (text) => {
        if (config.chatHome && !consent.consented) pendingServicePrompt = text;
        chat.quickFill(text);
    };
    refs.sendBtn?.addEventListener('click', () => chat.sendMsg());
    refs.input?.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') chat.sendMsg();
    });
    refs.voiceBtn?.addEventListener('click', () => speech.toggleVoiceInput((text) => {
        if (refs.input) refs.input.value = text;
        chat.sendMsg();
    }));
    refs.replay?.addEventListener('click', () => speech.replayLast());
    refs.greetingButton?.addEventListener('click', () => speech.speak(refs.welcome.textContent));
    refs.waitResume?.addEventListener('click', () => waitTone.prime());
    refs.chips.querySelectorAll('button').forEach((button) => {
        button.onclick = () => onQuick(button.dataset.prompt || button.textContent);
    });

    // 数字人个性化配置：非致命（失败仅告警，保持静态文案；加载本身是 §3.6 动态要求）。
    loadAiConfig(refs, { onQuick, chatHome: config.chatHome }).catch((error) => {
        console.warn('[VB-Banking] 数字人个性化配置装配失败（保持静态文案）:', error);
    });

    const instance = {
        el: container,
        config: Object.freeze({ ...config }),

        async startBusiness() {
            if (disposed) throw new Error('实例已销毁。');
            if (!capabilities.ok) throw new Error(capabilities.criticalMessage);
            if (capabilities.missingOptional.length) {
                console.warn('[VB-Banking] 部分能力缺失（已降级）:', capabilities.missingOptional.join('、'));
            }
            stomp.setChannelContext({ channelId: config.channelId, tenantId: config.tenantId });
            if (!currentProcessId) currentProcessId = '';
            await logAction('SESSION_STARTED', '客户打开远程银行', { channel: identity.channel }, '');
            sync.start({
                processId: currentProcessId,
                customerId: identity.ensure(config),
                // 回调隔离：单次状态应用失败必须可见（console.error），但绝不打断轮询循环——
                // 流程继续走即 §2.9.3「办理到底」在编排层的落地。
                onState: (state) => applyState(state).catch((error) => {
                    console.error('[VB-Banking] 节点状态应用失败（轮询继续）:', error);
                }),
                onError: (error) => console.warn('[flow] 首次校准失败（将重试）:', error?.message || error),
            });
            return instance;
        },

        async destroy() {
            if (disposed) return;
            disposed = true;
            allowCamera = undefined;
            evaluation.destroy();
            clearTimeout(peerPrepareTimer);
            peerPrepareTimer = null;
            sync.stop();
            customerSignal.stop();
            chat.dispose();
            consent.dispose();
            waitTone.stop();
            speech.dispose();
            session.release();
            teardown();             // 接缝全量复位：命令面/事件订阅/抓帧兜底（下次 mount 由 configure 重新装配）
            await media.release();
            nodeDialog.reset();
            VbSteps.close(refs.stepper);
            try {
                await logAction('SESSION_CLOSED', '客户关闭远程银行', {}, '');
            } catch (error) {
                // 拆卸不因留痕上报失败而中断，但失败必须可见（非静默成功/静默失败）。
                console.warn('[VB-Banking] 会话关闭留痕上报失败:', error);
            }
            container.textContent = '';
            container.classList.remove('vb-root');
            container.style.position = '';
            container.style.overflow = '';
            if (active === instance) active = null;
        },
    };

    active = instance;
    return instance;
}

export default { mount };
