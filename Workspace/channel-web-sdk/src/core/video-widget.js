/*
 * video-widget 加载与命令适配层（契约 §2.9.3）。
 *
 * 视频控件是独立程序（video-widget/ 交付），业务侧只通过命令 API 使用，
 * 严禁触碰 MediaStream / MediaRecorder / <video> DOM。本文件是 channel-web-sdk
 * 与控件之间的唯一接缝：命令转发 + 事件订阅。严格两态（fail-fast 兜底清算令）：
 *   缺席（未接入/未配置）→ null，业务按 §2.9.3 走无视频路径；
 *   异常（控件已装载但命令被拒）→ 原样上抛，绝不降级为 null / console.warn 静默。
 *
 * 强依赖契约只有两个：
 *   video.capture('local' | 'remote')  → Blob（人工授权现场照片依赖）
 *   video.record.seal()                → { fileSize }（fileSize === 0 不得宣告存证成功）
 * 除这两点外业务代码不得假设视频存在——无视频时业务必须仍能办理到底。
 *
 * 装载形态：
 *   1) config.widgetModule —— 控件 ESM 模块（`import * as mod from '…/vb-video.esm.js'`）
 *   2) config.widgetUrl    —— 注入 <script>，期望全局 window.VideoBankingWidget（UMD）
 *   两者都缺省时视为"控件未接入"，所有命令返回 null（缺席语义，§2.9.3）。
 *
 * 形态归一化（联调实测校正）：控件把命令 API 放在 `mod.video`、挂载放在 `mod.mount`，
 * 命令签名是 connect({peerId, processId, localStream, desktop})、
 * open({mode, layout:归一化对象})、record.start({processId, bizNo, nodeName, …})、
 * capture('remote'|'local')。适配层负责把 SDK 语义翻译过去，业务调用面保持不变。
 *
 * 换音视频厂商/控件版本的扩展约定：本文件是 SDK 侧唯一允许出现「控件命令签名
 * 与状态快照字段名」的地方（isConnected 等语义化接口对外收敛）——上层
 * （video-session / recording / instance）只消费语义，不读控件 schema。
 */

let widget = null;            // 命令 API 宿主（控件 video 单例）
let widgetRoot = null;        // 模块根（mount / destroy / version）
let loading = null;           // 加载中的 Promise，防重复注入
let disposed = false;         // teardown() 后置 true，configure()（新实例挂载）恢复
let captureFallback = null;   // 控件未接入时的抓帧兜底（由 app 层注册本地流抓帧）
const listeners = new Map();  // 事件名 → Set<cb>（SDK 侧）
const forwarded = new Map();  // 事件名 → 控件侧取消订阅函数
let pendingOpen = null;       // 控件配置：configure() 由 mount(el, config) 触发

/**
 * 控件 UMD 全局名：lead 已裁定「UMD 全局名唯一性」——本 SDK 是 window.VideoBanking，
 * 控件是 window.VideoBankingWidget。加载仍保留「注入前后引用 diff」作为防覆盖防御。
 */
export function configure(options = {}) {
    const next = {
        module: options.widgetModule || null,
        url: options.widgetUrl || '',
        styleUrl: options.widgetCssUrl || '',
        globalName: options.widgetGlobal || 'VideoBankingWidget',
        upload: typeof options.recordingUpload === 'function' ? options.recordingUpload : null,
    };
    // 配置变更（新实例/换产物）→ 丢弃旧引用，下次 ensure() 按新配置重新装载。
    const changed = !pendingOpen
        || pendingOpen.module !== next.module
        || pendingOpen.url !== next.url
        || pendingOpen.globalName !== next.globalName;
    pendingOpen = next;
    disposed = false;
    if (changed && widget) {
        detachForwards();
        widget = null;
        widgetRoot = null;
        loading = null;
    }
}

/** 控件未接入时的抓帧兜底提供者（app 层用本地预览流实现）。 */
export function setCaptureFallback(fn) {
    captureFallback = typeof fn === 'function' ? fn : null;
}

/** 把 SDK 侧已登记的事件订阅转发给控件（控件侧 on 返回取消订阅函数）。 */
function attachForwards(name) {
    if (!widget || typeof widget.on !== 'function') return;
    const names = name ? [name] : Array.from(listeners.keys());
    for (const eventName of names) {
        if (forwarded.has(eventName)) continue;
        const off = widget.on(eventName, (payload) => emitLocal(eventName, payload));
        forwarded.set(eventName, typeof off === 'function' ? off : () => {});
    }
}

function detachForwards() {
    for (const off of forwarded.values()) {
        try { off(); } catch { /* 控件已卸载 */ }
    }
    forwarded.clear();
}

/** 归一化装载结果：命令在 `mod.video`（或自带命令的宿主），挂载在模块根。 */
function adopt(mod) {
    if (!mod || typeof mod !== 'object') return null;
    const api = (mod.video && typeof mod.video.open === 'function') ? mod.video : mod;
    widget = api;
    widgetRoot = mod;
    attachForwards();
    return api;
}

function emitLocal(name, payload) {
    const set = listeners.get(name);
    if (!set) return;
    for (const fn of Array.from(set)) {
        try {
            fn(payload);
        } catch (error) {
            console.warn(`[video-widget] ${name} 回调异常:`, error);
        }
    }
}

function loadScript(url, globalName) {
    return new Promise((resolve) => {
        const existing = document.querySelector(`script[data-vb-widget="${url}"]`);
        // 注入前先记住当前全局引用：控件 UMD 装载后引用发生变化才说明装载完成。
        const before = window[globalName];
        const pick = () => {
            const after = window[globalName];
            if (after && after !== before) return after;
            if (!before && after) return after;
            // 已存在的引用如果自带控件特征（video 命令单例 + 版本号），也视为控件。
            if (after && after.video && typeof after.video.open === 'function' && after.version) return after;
            return null;
        };
        const poll = () => resolve(pick());
        if (existing) { setTimeout(poll, 0); return; }
        const script = document.createElement('script');
        script.src = url;
        script.async = true;
        script.dataset.vbWidget = url;
        script.onload = poll;
        script.onerror = () => resolve(null);   // 加载失败按未接入处理，不阻断业务
        document.head.appendChild(script);
    });
}

/** 确保控件已加载；返回命令宿主或 null（null ＝ 未接入，业务继续走无视频路径）。 */
export async function ensure() {
    if (widget) return widget;
    if (disposed) return null;
    if (loading) return loading;
    loading = (async () => {
        try {
            if (pendingOpen?.styleUrl && typeof document !== 'undefined'
                && !document.querySelector('link[data-vb-widget-style]')) {
                const link = document.createElement('link');
                link.rel = 'stylesheet';
                link.href = pendingOpen.styleUrl;
                link.setAttribute('data-vb-widget-style', '');
                document.head.appendChild(link);
            }
            if (pendingOpen && pendingOpen.module) {
                return adopt(pendingOpen.module);
            }
            if (pendingOpen && pendingOpen.url) {
                return adopt(await loadScript(pendingOpen.url, pendingOpen.globalName));
            }
        } catch (error) {
            // 装载失败＝控件缺席（脚本注入失败 / 模块异常 / 无 document 环境），
            // 按 §2.9.3 走无视频路径；已装载控件的命令拒绝不会经过这里（call 直接上抛）。
            console.warn('[video-widget] 控件装载失败，按未接入处理:', error);
        }
        return null;
    })();
    const result = await loading;
    loading = null;
    return result;
}

/**
 * 命令转发（严格两态，禁止第三态）：
 *   - 缺席：控件未装载、或该版本未提供此命令 → null。契约 §2.9.3 明文「无视频时
 *     业务必须仍能办理到底」，null 就是「无视频」的事实陈述，调用方不得据此宣告成功。
 *   - 异常：控件已装载但命令被拒 → 原样上抛（同步抛、异步 reject 都不接）。
 *     异常不得经本接缝降级为 null/console.warn 静默——是否转入无视频路径由业务层
 *     （video-session / recording）按 §2.9.3 自行决定并如实告警。
 */
function call(command, args = []) {
    if (!widget) return null;
    // 支持 'record.start' 这类点路径命令（对齐 §2.9.3 的 video.record.* 命名）。
    const parts = String(command).split('.');
    let host = widget;
    for (const part of parts) {
        host = host ? host[part] : null;
    }
    if (typeof host !== 'function') return null;
    return Promise.resolve(host.apply(widget, args));
}

export const video = {
    /**
     * 打开控件（契约 §2.9.2）：mode overlay / dock；layout 必须是**归一化布局对象**
     * （dock 下为 {order,heightRatio,height}）——绝不能传字符串，控件会把字符串
     * 展开进布局对象。pixel / draggable 可选。
     */
    async open(options = {}) {
        disposed = false;
        const instance = await ensure();
        if (!instance) return false;
        const payload = {};
        if (options.mode) payload.mode = options.mode;
        if (options.layout && typeof options.layout === 'object') payload.layout = options.layout;
        if (options.pixel) payload.pixel = options.pixel;
        if (options.draggable !== undefined) payload.draggable = Boolean(options.draggable);
        const result = await call('open', [payload]);
        return result !== null;
    },
    /**
     * 把控件视图落进宿主给定容器（widget.mount(container, options) → stage）。
     * 控件缺席返回 null；mount 执行期异常原样上抛（容器非法/控件内部故障不得在
     * 接缝里吞成 null），由业务层（video-session.engage）按 §2.9.3 转入无视频办理。
     * options 透传控件 mount 口径：{ toolbar, showLocalPreview, zIndex, placeholder, icons }。
     */
    async mountInto(container, options = {}) {
        if (!container) return null;
        const instance = await ensure();
        if (!instance) return null;
        const mount = typeof widgetRoot?.mount === 'function' ? widgetRoot.mount
            : (typeof instance.mount === 'function' ? instance.mount : null);
        if (!mount) return null;
        const { mode, ...rest } = options || {};   // mode 属于 open()，不进 mount 口径
        const stage = await mount(container, rest);
        return stage || null;
    },
    /** 卸载 stage（缺省卸载本模块挂过的全部 stage）；缺席无操作，异常上抛。 */
    unmount(stage) {
        const destroy = typeof widgetRoot?.destroy === 'function' ? widgetRoot.destroy
            : (widget && typeof widget.destroy === 'function' ? widget.destroy : null);
        if (!destroy) return;
        if (stage) destroy(stage);
        else destroy();
    },
    /** 关闭命令面：缺席返回 null；close 可能拒绝（控件清理失败会如实上抛），调用方负责收口。 */
    close() {
        return call('close', []);
    },
    /** 仅注册渠道端 Peer，等待服务端 VIDEO_ROOM_READY；不会打开窗口或申请媒体权限。 */
    async prepare(options = {}) {
        const instance = await ensure();
        if (!instance) return false;
        const payload = {};
        if (options.localPeerId) payload.localPeerId = options.localPeerId;
        if (options.processId) payload.processId = options.processId;
        if (options.peerId) payload.peerId = options.peerId;
        const result = await call('prepare', [payload]);
        return result !== null;
    },
    /**
     * 建链（联调实测按控件签名翻译）：
     *   agentPeerId → peerId（对端标识；缺省 = 本地见证，不订阅、call 保持 false）
     *   processId   透传；localStream 优先传 SDK 同意告知书后取得的流，
     *               避免控件在非用户手势上下文里再次取权（§3.6 红线）
     *   desktop     渠道端永不采集桌面（缺省 false）
     */
    async connect(options = {}) {
        const payload = { desktop: options.desktop === true };
        const peerId = options.peerId ?? options.agentPeerId;
        if (peerId) payload.peerId = peerId;
        if (options.localPeerId) payload.localPeerId = options.localPeerId;
        if (options.processId) payload.processId = options.processId;
        if (options.localStream) payload.localStream = options.localStream;
        if (options.constraints) payload.constraints = options.constraints;
        if (options.incomingOnly === true) payload.incomingOnly = true;
        const result = await call('connect', [payload]);
        return result !== null;
    },
    hangup() { return call('hangup', []); },
    updateParticipants(participants) { return call('updateParticipants', [participants]); },
    /** 接管模式：控件只接受 av / audio / released 三值（服务端下发为准，其余不转发）。 */
    takeover(mode) {
        if (mode !== 'av' && mode !== 'audio' && mode !== 'released') return null;
        return call('takeover', [mode]);
    },
    toggle(kind) { return call('toggle', [kind]); },

    /** 抓帧（人工授权现场照片依赖）：控件仅支持 remote / local 两源，其余归一到 remote。 */
    async capture(where = 'local') {
        const source = where === 'local' ? 'local' : 'remote';
        // 控件在场的抓帧异常一律上抛（强依赖返回值，失败必须让业务看见，§2.9.3）；
        // 只有「控件缺席」才回落 SDK 本地预览流（§3.6 媒体链），仍无画面则如实 null。
        const fromWidget = await call('capture', [source]);
        if (fromWidget instanceof Blob) return fromWidget;
        if (captureFallback) {
            const blob = await captureFallback(source);
            if (blob instanceof Blob) return blob;
        }
        return null;
    },

    record: {
        /** 开始一段录制：meta 走控件口径 {processId,bizNo,nodeName,segmentIndex,handlerType}。 */
        start(meta = {}) {
            const upload = pendingOpen?.upload;
            return call('record.start', [{ ...meta, ...(upload ? { upload } : {}) }]);
        },
        /** 封存回执：未接入时返回 null，调用方不得据此宣告存证成功。 */
        async seal() {
            const receipt = await call('record.seal', []);
            return receipt && typeof receipt === 'object' ? receipt : null;
        },
        resume() { return call('record.resume', []); },
    },

    on(name, callback) {
        if (typeof callback !== 'function') return () => {};
        if (!listeners.has(name)) listeners.set(name, new Set());
        listeners.get(name).add(callback);
        attachForwards(name);
        return () => {
            listeners.get(name)?.delete(callback);
            if (!listeners.get(name)?.size) {
                forwarded.get(name)?.();
                forwarded.delete(name);
            }
        };
    },

    async getState() {
        const state = await call('getState', []);
        return state || null;
    },

    /**
     * 连通判据（对齐 navis videoConnected 的 remote 口径）。
     * 快照字段（call / hasRemote / connection）是控件私有 schema，只允许出现在本
     * 接缝内——换控件/换厂商时上层（video-session、等待音乐）零改动。
     * 缺席或读取失败一律 false（失败侧判据，非成功宣告）：等待音 §3.6 要求
     * `!connected` 才播，判据本身不得向业务抛错打断轮询。
     */
    async isConnected() {
        try {
            const state = await call('getState', []);
            return Boolean(state && state.hasRemote);
        } catch {
            return false;
        }
    },

    /** 控件实例把状态回传给业务时走这里（加载器/联调层调用）。 */
    forward(name, payload) {
        emitLocal(name, payload);
    },

    get available() {
        return Boolean(widget);
    },
};

/** 卸载（destroy 时调用）：关闭控件并丢弃全部订阅；close 被拒也必须走完清理（错误可见）。 */
export function teardown() {
    disposed = true;
    try {
        const closing = video.close();
        if (closing && typeof closing.catch === 'function') {
            closing.catch((error) => console.warn('[video-widget] teardown 关闭控件失败:', error));
        }
    } catch (error) {
        console.warn('[video-widget] teardown 关闭控件失败:', error);
    }
    detachForwards();
    widget = null;
    widgetRoot = null;
    loading = null;
    listeners.clear();
    captureFallback = null;
}

export default video;
