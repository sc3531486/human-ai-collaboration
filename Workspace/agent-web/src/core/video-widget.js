/**
 * 视频控件加载与命令适配层（契约 §2.9）—— agent-web 侧唯一允许接触 video-widget 的文件。
 *
 * 职责收口：
 *  1. **加载**：只从独立发布地址加载 UMD script `window.VideoBankingWidget`；坐席构建不得
 *     静态 import 控件制品，否则控件无法独立升级或回滚。
 *     （§8.2 全局名唯一：channel 端是 window.VideoBanking，两份共存互不覆盖）。
 *     样式通过 link 注入，基址 VITE_VIDEO_WIDGET_BASE，
 *     默认 `/video-widget/`）。
 *  2. **承载**：overlay 宿主容器由端壳（App.vue）提供并常驻；stage 只挂一次，
 *     不随业务节点/视图切换重建（§8.4-2）。比例布局、像素覆盖、边界 clamp、拖拽回写
 *     sessionStorage（归一化值）全部由控件内部完成（§2.9.1-2.9.2），open 后还原落位。
 *  3. **命令门面**：业务/场景代码只 `import { video } from '@/core/video-widget.js'`
 *     （§2.9.3 唯一入口）。本层只做转发与"控件未加载时的中性快照"，
 *     不碰 MediaStream / MediaRecorder / <video> DOM，也不持有任何媒体对象。
 *
 * 业务红线不变（§2.9.3）：唯一强依赖是 `capture()` 与 `record.seal()` 的返回值；
 * 控件加载失败时各命令按语义降级（抓帧返回 null、封存回执 0 字节、关闭/挂断空转），
 * **无视频时业务必须仍能办理到底**。
 */
import { bus, report } from './bus.js';
import { identity } from './identity.js';

const UMD_GLOBAL = 'VideoBankingWidget'; // §8.2：全局名唯一，不得与 channel 端混淆
/*
 * dist 产物路径（相对本文件 src/core → 上三级即工作区根，video-widget 与 agent-web 平级）：
 *   UMD: ${VITE_VIDEO_WIDGET_BASE}/vb-video.umd.js
 *   CSS: ${VITE_VIDEO_WIDGET_BASE}/vb-video.css
 */

/** 控件未加载时的中性快照：字段形状对齐控件 state，订阅方读取无需判空。 */
const IDLE_STATE = Object.freeze({
    mounted: false,
    mode: 'overlay',
    layout: { left: 0.72, top: 0.05, width: 0.26, height: 0.3 }, // §2.9.2 overlay 默认位
    pixel: null,
    draggable: false,
    connection: 'idle',
    call: false,
    recording: false,
    takeover: 'av',
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
});

/** 控件模块面（ESM 命名空间与 UMD 全局都以 `video` + `mount` 判定）。 */
function faceOf(mod) {
    if (!mod) return null;
    if (mod.video && typeof mod.mount === 'function') return mod;
    if (mod.default?.video && typeof mod.default.mount === 'function') return mod.default;
    return null;
}

let widget = null;      // 已加载的控件模块面；null = 尚未加载
let loading = null;     // 进行中的加载 Promise（并发 open 只加载一次）
let styleLoaded = false;
let stage = null;       // 当前已挂载的视图；是否常驻由业务系统的窗口策略决定
let hostBound = null;   // stage 当前所在宿主容器
let preferredHost = null; // 端壳登记的承载位；登记本身不创建视频窗口

/* ---------- 事件转发：业务可在控件加载前订阅（SideRail onMounted 早于 App onMounted） ---------- */
const listeners = new Map();
let hangupHandler = null;
const forwarded = new Set();

function emit(event, payload) {
    const handlers = listeners.get(event);
    if (!handlers) return;
    for (const handler of [...handlers]) {
        try { handler(payload); }
        catch (error) { report(`视频事件订阅 ${event}`, error, { sticky: true }); }
    }
}

function maybeForward(event) {
    if (!widget || forwarded.has(event)) return;
    forwarded.add(event);
    widget.video.on(event, payload => emit(event, payload));
}

function on(event, handler) {
    // 传错处理函数会让该事件永远收不到回调且无任何报错：注册期直接抛出。
    if (typeof handler !== 'function') throw new TypeError(`视频事件订阅处理器必须是函数：${event}`);
    if (!listeners.has(event)) listeners.set(event, new Set());
    listeners.get(event).add(handler);
    maybeForward(event);
    return () => off(event, handler);
}

function off(event, handler) {
    listeners.get(event)?.delete(handler);
}

function getState() {
    return widget ? widget.video.getState() : { ...IDLE_STATE };
}

/* ---------- 加载（当前仅 UMD：契约 §0.1 允许 ESM/UMD 两种方式，§8.2 全局名唯一） ---------- */

function umdBase() {
    const raw = import.meta.env?.VITE_VIDEO_WIDGET_BASE || '/video-widget/';
    return raw.endsWith('/') ? raw : `${raw}/`;
}

function loadUmd() {
    if (typeof document === 'undefined') {
        return Promise.reject(new Error('UMD 加载只能在浏览器环境执行'));
    }
    const src = `${umdBase()}vb-video.umd.js`;
    return new Promise((resolve, reject) => {
        const finish = () => {
            const face = faceOf(globalThis[UMD_GLOBAL]);
            if (!face) reject(new Error(`UMD 全局 window.${UMD_GLOBAL} 未就绪`));
            else resolve(face);
        };
        const exist = document.querySelector('script[data-vb-video-umd]');
        if (exist) {
            if (exist.dataset.vbVideoUmdState === 'done') finish();
            else if (exist.dataset.vbVideoUmdState === 'error') {
                exist.remove();
                reject(new Error(`UMD 脚本加载失败：${src}`));
            }
            else {
                // 上一次加载失败残留的标签：补挂监听前先摘掉失败态，否则 load/error 已经
                // 永远不会再触发，Promise 会挂死在那里（无超时的静默卡住）。
                exist.addEventListener('load', finish, { once: true });
                exist.addEventListener('error', () => {
                    exist.remove();
                    reject(new Error(`UMD 脚本加载失败：${src}`));
                }, { once: true });
            }
            return;
        }
        const script = document.createElement('script');
        script.src = src;
        script.async = true;
        script.dataset.vbVideoUmd = '';
        script.onload = () => { script.dataset.vbVideoUmdState = 'done'; finish(); };
        script.onerror = () => { script.dataset.vbVideoUmdState = 'error'; script.remove(); reject(new Error(`UMD 脚本加载失败：${src}`)); };
        document.head.appendChild(script);
    });
}

async function loadStyle() {
    if (styleLoaded) return;
    // 控件以独立制品发布：固定相对 import 会被 Vite 打回坐席端产物，不能使用。
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = `${umdBase()}vb-video.css`;
    link.dataset.vbVideoUmd = '';
    document.head.appendChild(link);
    styleLoaded = true;
}

async function load() {
    if (widget) return widget;
    if (loading) return loading;
    loading = (async () => {
        const mod = await loadUmd();
        await loadStyle();
        widget = mod;
        // 控件加载前注册的订阅（如 SideRail）在这里补接线。
        [...listeners.keys()].forEach(maybeForward);
        return widget;
    })();
    try {
        return await loading;
    } catch (error) {
        loading = null; // 允许下次调用重试（瞬时失败不永久锁死）
        throw error;
    }
}

/* ---------- 承载：宿主容器 + stage 常驻挂载 ---------- */

function ensureStage(mod, host) {
    if (stage && hostBound === host) return stage;
    if (stage) {
        // 换容器重挂时旧 stage 清理失败必须可见：失败会残留旧 DOM 与计时器。
        try { mod.destroy(stage); }
        catch (error) { report('视频窗口卸载（换容器）', error, { sticky: true }); }
        stage = null;
    }
    stage = mod.mount(host, {
        onHangupRequest: async () => {
            if (!hangupHandler) throw new Error('业务挂断接口尚未绑定');
            await hangupHandler();
        },
        // 工具栏抓帧按钮 → 对齐 navis 快照按钮：存本机 PNG；无画面时如实提示。
        onCapture: blob => {
            if (!blob) return bus.emit('ui:toast', { message: '尚未收到客户画面，无法拍照', error: true });
            try {
                const url = URL.createObjectURL(blob);
                const link = document.createElement('a');
                const bizNo = getState().bizNo;
                link.href = url;
                link.download = bizNo ? `客户画面-${bizNo}.png` : '客户画面.png';
                link.click();
                URL.revokeObjectURL(url);
                bus.emit('ui:toast', { message: '客户画面已保存到本机', error: false });
            } catch (error) {
                // 保存失败必须报失败——原先无论成败都提示"已保存"，是典型的假成功。
                report('客户画面保存', error instanceof Error ? error : new Error('浏览器拒绝了下载操作'));
            }
        }
    });
    hostBound = host;
    // 宿主容器 pointer-events:none（浮层铺满视口却不挡业务点击），交互还给控件根。
    // data-vb-stage 是控件公开挂载标记（stage.js 同步设置），缺失意味着控件将"看得见
    // 点不动"——这是必须暴露的契约漂移，不能靠宿主样式兜底成静默不可用。
    const root = host.querySelector('[data-vb-stage]');
    if (!root) report('视频窗口挂载', new Error('控件未输出 [data-vb-stage] 标记，窗口将无法交互'), { sticky: true });
    else root.style.pointerEvents = 'auto';
    return stage;
}

/**
 * 登记业务系统提供的承载位，但不创建控件窗口。
 * 窗口何时出现由业务系统的接听策略决定：answer 时 open，hangup 时 unmount；
 * 若某渠道需要常驻，只需在初始化阶段 open，不需要修改视频控件。
 */
function setHost(host) {
    preferredHost = host || null;
}

/* ---------- §2.9.3 命令 API 门面 ---------- */

async function open(config = {}) {
    const mod = await load();
    const { host, restore = true, ...options } = config;
    // 承载位必须由端壳显式登记（App.vue 挂载时 setHost）：悄悄在 body 上造一个浮层
    // 会让"端壳没接线"这类装配错误永远排查不出来。
    const target = host || hostBound || preferredHost;
    if (!target) throw new Error('视频承载位未登记：端壳必须在初始化时调用 video.setHost(host)');
    ensureStage(mod, target);
    await mod.video.open(options);
    // 拖拽落位回写的是 sessionStorage 归一化值（§2.9.2），重新打开时还原；
    // dock 模式的落位是顺序/占比，不受 overlay 落位记录影响。
    if (options.mode !== 'dock' && restore) mod.video.restoreLayout();
    return getState();
}

/** 关闭媒体/传输但保留 stage DOM，供常驻窗口模式使用。 */
async function close() {
    if (!widget) return { ...IDLE_STATE }; // 控件从未加载：空转即可，不得阻断业务释放
    await widget.video.close();
    return getState();
}

/**
 * 卸载窗口：结束控件会话并移除其 DOM。业务系统在“临时窗口”策略的挂断、释放时调用；
 * 常驻模式只调用 close()/hangup()，保留窗口等待下一通。
 */
async function unmount() {
    if (!widget) return { ...IDLE_STATE };
    await widget.video.close();
    if (stage) {
        // 重复卸载之外的清理失败必须可见：stage 残留会让下一次 open 挂到失效 DOM 上。
        try { widget.destroy(stage); }
        catch (error) { report('视频窗口卸载', error, { sticky: true }); }
        stage = null;
    }
    hostBound = null;
    return getState();
}

/** 建链：坐席端接听的用户手势内调用（§3.4 桌面采集手势内申请）。加载失败如实抛出。 */
async function connect(config = {}) {
    const mod = await load();
    return mod.video.connect(config);
}

async function prepare(config = {}) {
    const mod = await load();
    return mod.video.prepare(config);
}
async function updateParticipants(participants) { const mod=await load();return mod.video.updateParticipants(participants); }

/** 挂断：控件从未加载时视为无事可挂，不因视频缺席让业务挂断失败。 */
async function hangup() {
    if (!widget) return { ...IDLE_STATE };
    await widget.video.hangup();
    return getState();
}

async function takeover(mode) {
    const mod = await load();
    return mod.video.takeover(mode);
}

async function toggle(kind) {
    const mod = await load();
    return mod.video.toggle(kind);
}

/** 抓帧：返回 Blob；无画面时控件返回 null（不伪造占位图）。 */
async function capture(source = 'remote') {
    const mod = await load();
    return mod.video.capture(source);
}

async function recordStart(meta = {}) {
    const mod = await load();
    return mod.video.record.start({ ...meta, upload: meta.upload || uploadRecording });
}

/** 坐席宿主负责接入平台归档接口；独立控件本身不内置任何业务 URL。 */
async function uploadRecording(form) {
    const response = await fetch('/api/balance/record/stream-chunk', { method: 'POST', headers: { 'X-CSRF-Token': identity.csrfToken || '' }, body: form });
    let receipt;
    try { receipt = await response.json(); }
    catch (error) {
        // 分片上传的回执不是 JSON：把 HTTP 状态带进错误，补传队列才能定位是哪一拍坏了。
        const parseError = new Error(`录像分片回执异常（HTTP ${response.status}，响应不是合法 JSON）`);
        parseError.status = response.status;
        parseError.code = `HTTP_${response.status}`;
        throw parseError;
    }
    if (!response.ok || receipt.success === false) {
        const error = new Error(receipt.error || receipt.message || '录像分片待补传');
        error.status = response.status;
        error.code = response.ok ? `BIZ_${receipt.code || 'FAIL'}` : `HTTP_${response.status}`;
        throw error;
    }
    return receipt;
}

/** 封存：回执含 fileSize；0 字节由业务侧如实提示（§3.4），本层不加工结果。 */
async function recordSeal() {
    const mod = await load();
    return mod.video.record.seal();
}

/** 断点补传（页面重载后），与 seal 同一 0 字节口径。 */
async function recordResume() {
    const mod = await load();
    return mod.video.record.resume();
}

export const video = Object.freeze({
    setHangupHandler: handler => { hangupHandler = handler; },
    setHost,
    open,
    close,
    unmount,
    connect,
    prepare,
    updateParticipants,
    hangup,
    takeover,
    toggle,
    capture,
    record: Object.freeze({ start: recordStart, seal: recordSeal, resume: recordResume }),
    on,
    off,
    getState
});

/** 供端壳预热/排障显式触发加载（非契约面，业务代码无需调用）。 */
export { load as loadVideoWidget };

export default video;
