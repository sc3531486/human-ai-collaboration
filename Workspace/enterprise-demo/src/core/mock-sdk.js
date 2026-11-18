/*
 * channel-web-sdk 桩件（mock VideoBanking）。
 *
 * 用途：channel-web-sdk/dist 构建产物就绪前，先按 §6.1 公开接口
 * （mount(el, config) → { destroy(), clientId }）与 bridge contract 提供可运行替身，
 * 让渠道接入代码、宿主能力替换与 Vite 反代先行联调；产物就绪后由
 * sdk-loader 自动切换到真实 SDK，宿主侧代码零改动。
 *
 * 桩件忠实模拟 SDK 的两条硬行为（契约 §3.6 / §6.2）：
 *   - 同意告知书之前不请求媒体权限；
 *   - 每个能力域先问宿主桥（config.bridge 或 window.VBBankingBridge），
 *     宿主未实现则走浏览器兜底，并记录 path: 'browser'。
 *
 * SDK 只拥有一块容器：所有 DOM 都建在 mount 传入的容器内，
 * class 一律 vb- 前缀，不碰宿主页面结构、不控制宿主路由。
 */
import { logBridgeCall } from './bridge-log.js';

const STYLE_ID = 'vb-mock-sdk-style';

const CSS = `
.vb-host { font-family: inherit; color: #1e293b; }
.vb-host * { box-sizing: border-box; }
.vb-mock { border: 1px dashed #2563eb; border-radius: 10px; background: #f8fbff; padding: 18px; }
.vb-mock-title { display: flex; align-items: center; gap: 8px; font-size: 15px; font-weight: 700; color: #1d4ed8; margin-bottom: 4px; }
.vb-mock-badge { font-size: 11px; font-weight: 600; background: #dbeafe; color: #1d4ed8; border: 1px solid #93c5fd; border-radius: 999px; padding: 1px 8px; }
.vb-mock-sub { font-size: 12.5px; color: #64748b; margin-bottom: 12px; line-height: 1.6; }
.vb-mock-consent { background: #fff7ed; border: 1px solid #fed7aa; border-radius: 8px; padding: 12px; font-size: 13px; color: #7c2d12; margin-bottom: 12px; }
.vb-mock-consent button { margin-top: 8px; }
.vb-mock-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 8px; margin-bottom: 12px; }
.vb-cap { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 9px 10px; font-size: 12.5px; }
.vb-cap-name { font-weight: 600; color: #0f172a; display: flex; justify-content: space-between; align-items: center; gap: 6px; }
.vb-cap-state { font-size: 11px; border-radius: 999px; padding: 0 7px; line-height: 18px; white-space: nowrap; }
.vb-cap-state.native { background: #dcfce7; color: #166534; border: 1px solid #86efac; }
.vb-cap-state.browser { background: #fef9c3; color: #854d0e; border: 1px solid #fde68a; }
.vb-cap-state.idle { background: #f1f5f9; color: #64748b; border: 1px solid #e2e8f0; }
.vb-cap-desc { color: #64748b; margin-top: 4px; line-height: 1.5; }
.vb-mock-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.vb-mock-actions button { font-size: 13px; padding: 7px 14px; border-radius: 7px; border: 1px solid #2563eb; background: #2563eb; color: #fff; cursor: pointer; }
.vb-mock-actions button:disabled { opacity: .45; cursor: not-allowed; }
.vb-mock-actions button.secondary { background: #fff; color: #2563eb; }
.vb-mock-result { margin-top: 12px; font-size: 12.5px; background: #0f172a; color: #a5f3fc; border-radius: 8px; padding: 10px 12px; min-height: 42px; white-space: pre-wrap; word-break: break-all; max-height: 180px; overflow: auto; }
.vb-mock-foot { margin-top: 10px; font-size: 11.5px; color: #94a3b8; }
`;

function ensureStyle() {
    if (typeof document === 'undefined' || document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = CSS;
    document.head.appendChild(style);
}

/** 每个能力域的浏览器兜底（contract.md 的「未实现 → 降级」语义），并记 path: 'browser'。 */
const FALLBACK = {
    media: {
        async acquire(constraints = { video: true, audio: true }) {
            logBridgeCall({ domain: 'media', method: 'acquire', path: 'browser', detail: '宿主未实现 → navigator.mediaDevices.getUserMedia' });
            try {
                return await navigator.mediaDevices.getUserMedia(constraints);
            } catch {
                logBridgeCall({ domain: 'media', method: 'acquire', path: 'browser', detail: 'getUserMedia 失败 → 画布模拟流（原型演示）' });
                return mockStream();
            }
        },
        async release(stream) {
            logBridgeCall({ domain: 'media', method: 'release', path: 'browser', detail: '宿主未实现 → track.stop()' });
            if (stream && stream.getTracks) stream.getTracks().forEach((t) => t.stop());
        },
    },
    album: {
        pick() {
            logBridgeCall({ domain: 'album', method: 'pick', path: 'browser', detail: '宿主未实现 → <input type=file>' });
            return new Promise((resolve, reject) => {
                const input = document.createElement('input');
                input.type = 'file';
                input.accept = 'image/*';
                input.style.display = 'none';
                input.onchange = () => {
                    const file = input.files && input.files[0];
                    input.remove();
                    file ? resolve(file) : reject(new Error('未选择照片'));
                };
                document.body.appendChild(input);
                input.click();
            });
        },
    },
    ocr: {
        recognize() {
            logBridgeCall({ domain: 'ocr', method: 'recognize', path: 'browser', detail: '宿主未实现 → 转后端能力接口（桩件模拟）' });
            return Promise.resolve({ raw: { source: 'backend-capability-mock' } });
        },
    },
    face: {
        verify(image, options = {}) {
            logBridgeCall({ domain: 'face', method: 'verify', path: 'browser', detail: '宿主未实现 → 后端核身能力（桩件模拟）' });
            return Promise.resolve({ score: 0.5, passed: false, threshold: options.threshold || 0.9, auditId: 'backend-pending' });
        },
    },
    signature: {
        open() {
            logBridgeCall({ domain: 'signature', method: 'open', path: 'browser', detail: '宿主未实现 → SDK Canvas 手写板（桩件模拟）' });
            return Promise.resolve(new Blob([('sdk-canvas-pad')], { type: 'text/plain' }));
        },
    },
    record: {
        append(chunk) {
            logBridgeCall({ domain: 'record', method: 'append', path: 'browser', detail: '宿主未实现 → Web 分片上传队列（桩件模拟）' });
            return Promise.resolve({ fileSize: chunk ? chunk.size : 0 });
        },
    },
    biometrics: {
        auth() {
            // contract.md §7：未实现 → 跳过，不做降级替代（不臆造认证结果）。
            logBridgeCall({ domain: 'biometrics', method: 'auth', path: 'browser', detail: '宿主未实现 → 跳过，不臆造认证结果' });
            return Promise.resolve({ passed: false, method: 'none' });
        },
    },
    device: {
        readIdCard() {
            logBridgeCall({ domain: 'device', method: 'readIdCard', path: 'browser', detail: '宿主未实现 → 无外设兜底（桩件模拟）' });
            return Promise.resolve(null);
        },
        printReceipt() {
            logBridgeCall({ domain: 'device', method: 'printReceipt', path: 'browser', detail: '宿主未实现 → 无打印机兜底（桩件模拟）' });
            return Promise.resolve({ accepted: false });
        },
    },
    navigate: {
        // contract.md §9：未实现 → SDK 不主动跳转。
        async close() { logBridgeCall({ domain: 'navigate', method: 'close', path: 'browser', detail: '宿主未实现 → SDK 不主动跳转，仅展示结果' }); },
        async to() { logBridgeCall({ domain: 'navigate', method: 'to', path: 'browser', detail: '宿主未实现 → SDK 不主动跳转' }); },
    },
    telemetry: {
        // contract.md §8：未实现 → 丢弃，不影响业务。
        async log() { logBridgeCall({ domain: 'telemetry', method: 'log', path: 'browser', detail: '宿主未实现 → 埋点丢弃' }); },
    },
};

function mockStream() {
    const canvas = document.createElement('canvas');
    canvas.width = 480;
    canvas.height = 360;
    const ctx = canvas.getContext('2d');
    const draw = () => {
        ctx.fillStyle = '#0f172a';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.fillStyle = '#38bdf8';
        ctx.font = 'bold 20px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('模拟视频流', canvas.width / 2, canvas.height / 2);
    };
    draw();
    const timer = setInterval(draw, 1000);
    const stream = canvas.captureStream(25);
    const rawStop = stream.stop ? stream.stop.bind(stream) : null;
    stream.stop = () => { clearInterval(timer); if (rawStop) rawStop(); };
    return stream;
}

/** 能力域展示清单（与宿主 DOMAINS 一一对应，便于示例页矩阵比对）。 */
const CAPABILITIES = [
    { id: 'media', name: '取流 media' },
    { id: 'album', name: '相册 album' },
    { id: 'ocr', name: '证件 OCR' },
    { id: 'face', name: '人脸核身 face' },
    { id: 'signature', name: '签字板 signature' },
    { id: 'record', name: '录像 record' },
    { id: 'biometrics', name: '生物认证 biometrics' },
    { id: 'device', name: '自助外设 device' },
    { id: 'navigate', name: '宿主路由 navigate' },
    { id: 'telemetry', name: '埋点 telemetry' },
];

/**
 * 按契约解析一次能力调用：宿主桥优先，缺失走兜底。
 * 返回 { path: 'native' | 'browser', result }。
 */
function call(bridge, domain, method, args) {
    const hosted = bridge && bridge[domain] && typeof bridge[domain][method] === 'function' ? bridge[domain][method] : null;
    if (hosted) {
        // 宿主实现抛错按「未实现」处理（contract.md §0：业务不被宿主缺陷卡死）。
        return Promise.resolve()
            .then(() => hosted(...args))
            .then((result) => {
                if (result === null || result === undefined) throw new Error('host returned empty');
                return { path: 'native', result };
            })
            .catch(() => {
                const fb = FALLBACK[domain] && FALLBACK[domain][method];
                return fb ? Promise.resolve(fb(...args)).then((r) => ({ path: 'browser', result: r })) : { path: 'browser', result: null };
            });
    }
    const fb = FALLBACK[domain] && FALLBACK[domain][method];
    if (!fb) return Promise.resolve({ path: 'browser', result: null });
    return Promise.resolve(fb(...args)).then((r) => ({ path: 'browser', result: r }));
}

function describe(value) {
    if (value === null || value === undefined) return String(value);
    if (value instanceof MediaStream) return `MediaStream(tracks=${value.getTracks().length})`;
    if (value instanceof Blob) return `Blob(size=${value.size}, type=${value.type || 'n/a'})`;
    if (typeof value === 'object') return JSON.stringify(value, null, 2);
    return String(value);
}

/** 桩件 mount(el, config) —— 与真实 SDK 同签名同语义。 */
function mount(el, config = {}) {
    const container = typeof el === 'string'
        ? (typeof document !== 'undefined' ? document.querySelector(el) : null)
        : el;
    if (!container) throw new Error('SDK 挂载容器不存在');
    ensureStyle();

    const bridge = config.bridge || (typeof window !== 'undefined' ? window.VBBankingBridge : null);
    const clientId = config.customerId || 'cust-mock-0001';

    const host = document.createElement('div');
    host.className = 'vb-host';
    container.appendChild(host);

    let consented = false;
    const state = { stream: null };

    host.innerHTML = `
        <section class="vb-mock">
            <div class="vb-mock-title">channel-web-sdk <span class="vb-mock-badge">桩件模式</span></div>
            <div class="vb-mock-sub">
                dist 产物未就绪，当前为接口对齐的桩件（mount/el/config、桥接优先、浏览器兜底同真实 SDK）。
                产物就绪后执行 <code>npm run vendor:sdk</code> 即切换为真实 SDK，宿主代码无需改动。
            </div>
            <div class="vb-mock-consent" data-role="consent">
                开始办理前须同意《业务双录与隐私授权告知书》。<strong>同意之前不请求任何媒体权限</strong>（契约 §3.6）。
                <br><button type="button" data-action="consent">同意并开始服务</button>
            </div>
            <div class="vb-mock-grid" data-role="grid"></div>
            <div class="vb-mock-actions">
                <button type="button" data-action="acquire" disabled>申请视频流</button>
                <button type="button" class="secondary" data-action="release" disabled>释放视频流</button>
                <button type="button" class="secondary" data-action="telemetry" disabled>上报埋点</button>
                <button type="button" class="secondary" data-action="navigate" disabled>跳转宿主页面</button>
                <button type="button" class="secondary" data-action="close" disabled>关闭 SDK 容器</button>
            </div>
            <div class="vb-mock-result" data-role="result">等待同意告知书……</div>
            <div class="vb-mock-foot">SDK 只拥有本容器：宿主路由、布局与页面生命周期均由渠道方保留（契约 §6.1）。</div>
        </section>
    `;

    const grid = host.querySelector('[data-role="grid"]');
    const resultEl = host.querySelector('[data-role="result"]');
    const capState = {};
    for (const cap of CAPABILITIES) {
        capState[cap.id] = 'idle';
        const item = document.createElement('div');
        item.className = 'vb-cap';
        item.innerHTML = `
            <div class="vb-cap-name">${cap.name}<span class="vb-cap-state idle" data-cap="${cap.id}">未调用</span></div>
            <div class="vb-cap-desc" data-desc="${cap.id}">${bridge && bridge[cap.id] ? '宿主已实现 → 原生路径' : '宿主未实现 → 浏览器兜底'}</div>
        `;
        grid.appendChild(item);
    }

    function mark(id, path) {
        capState[id] = path;
        const chip = host.querySelector(`[data-cap="${id}"]`);
        if (!chip) return;
        chip.className = 'vb-cap-state ' + path;
        chip.textContent = path === 'native' ? '原生' : path === 'browser' ? 'Web 兜底' : '未调用';
    }

    function show(text) {
        resultEl.textContent = typeof text === 'string' ? text : describe(text);
    }

    async function run(domain, method, args, label) {
        show(`${label} …`);
        const { path, result } = await call(bridge, domain, method, args);
        mark(domain, path);
        show(`【${label}】路径：${path === 'native' ? '宿主原生' : '浏览器兜底'}\n${describe(result)}`);
        return result;
    }

    function setAction(name, enabled) {
        const btn = host.querySelector(`[data-action="${name}"]`);
        if (btn) btn.disabled = !enabled;
    }

    host.addEventListener('click', async (event) => {
        const action = event.target && event.target.getAttribute && event.target.getAttribute('data-action');
        if (!action) return;
        try {
            if (action === 'consent') {
                consented = true;
                host.querySelector('[data-role="consent"]').innerHTML = '<strong>已同意告知书</strong>，媒体权限在此之后才允许申请（契约 §3.6）。';
                ['acquire', 'telemetry', 'navigate', 'close'].forEach((name) => setAction(name, true));
                setAction('release', true);
                await run('telemetry', 'log', ['consent_agreed', { channel: config.channel || 'enterprise-web' }], '同意告知书埋点');
                show('已同意，可申请视频流或测试各能力域。');
            } else if (action === 'acquire') {
                if (!consented) { show('未同意告知书，不请求媒体权限。'); return; }
                state.stream = await run('media', 'acquire', [{ video: true, audio: true }], '申请视频流');
            } else if (action === 'release') {
                if (state.stream) { await run('media', 'release', [state.stream], '释放视频流'); state.stream = null; }
                else show('当前没有已获取的视频流。');
            } else if (action === 'telemetry') {
                await run('telemetry', 'log', ['demo_click', { at: Date.now() }], '埋点上报');
            } else if (action === 'navigate') {
                await run('navigate', 'to', ['home', { from: 'sdk' }], '宿主路由跳转');
            } else if (action === 'close') {
                await run('navigate', 'close', [], '关闭 SDK 容器');
            }
        } catch (error) {
            show(`${label(action)}失败：${error && error.message ? error.message : String(error)}`);
        }
    });

    if (config.onEvent) {
        try { config.onEvent('mounted', { mode: 'mock', clientId }); } catch { /* 事件回调异常不影响挂载 */ }
    }

    return {
        destroy() {
            if (state.stream) state.stream.getTracks && state.stream.getTracks().forEach((t) => t.stop());
            if (host.parentNode) host.parentNode.removeChild(host);
        },
        get clientId() { return clientId; },
    };
}

function label(action) {
    return { acquire: '申请视频流', release: '释放视频流', telemetry: '埋点上报', navigate: '宿主路由跳转', close: '关闭 SDK 容器', consent: '同意告知书' }[action] || '调用';
}

/** 与真实 SDK UMD 全局（window.VideoBanking）同形：{ mount, version }。 */
export function loadMockSdk() {
    const api = { mount, version: 'mock-1.0.0' };
    if (typeof window !== 'undefined' && !window.VideoBanking) window.VideoBanking = api;
    return api;
}

export default { loadMockSdk };
