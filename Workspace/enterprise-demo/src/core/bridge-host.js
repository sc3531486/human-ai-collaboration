/*
 * 宿主 JSBridge mock（契约 §6.2 十能力域 + contract.md 可选 network）。
 *
 * 定位：企业网银「渠道方已有能力不重写」的示例实现——
 *   media/album/ocr/face/signature/record/biometrics/device/navigate/telemetry
 * 每个能力域可独立开关（enabled），演示两条路径：
 *   已实现 → SDK 调宿主原生能力（path: native）
 *   未实现 → 能力对象缺省/返回 null，SDK 走浏览器兜底（path: browser）
 *
 * 真实渠道接入时：把各域实现替换为 App/外设的原生桥（或删掉不实现的域），
 * SDK 侧零改动——这正是「宿主能力替换」的边界。
 *
 * 注意：本文件不在模块顶层触碰 window/document，便于 scripts/check.mjs 在 Node 下自检。
 */
import { logBridgeCall } from './bridge-log.js';

/** 十能力域元数据：示例页能力矩阵直接读它。 */
export const DOMAINS = [
    { id: 'media', name: '取流', desc: 'App WebView 的 getUserMedia 受限时由宿主原生取流' },
    { id: 'album', name: '相册选图', desc: '渠道方已有相册/拍照入口' },
    { id: 'ocr', name: '证件识别', desc: '渠道方已有 OCR，复用不重写' },
    { id: 'face', name: '人脸核身', desc: '手机银行/企业网银常见自有核身能力' },
    { id: 'signature', name: '签字板', desc: '自助设备物理签字板或渠道自有手写页' },
    { id: 'record', name: '录像存储', desc: '渠道自有录像落盘/上传通道' },
    { id: 'biometrics', name: '生物认证', desc: '高风险交易二次确认（指纹/面容/U 盾）' },
    { id: 'device', name: '自助外设', desc: '身份证阅读器、凭条打印机等柜面外设' },
    { id: 'navigate', name: '宿主路由', desc: '关闭 SDK 容器、跳转渠道自有页面' },
    { id: 'telemetry', name: '埋点', desc: '渠道方自有埋点体系' },
    { id: 'network', name: '网络鉴权（可选）', desc: 'contract.md §10：渠道票据与后端地址覆盖' },
];

/** 生成一张占位图片 Blob（宿主 mock 相册/OCR 输入用）。 */
function placeholderImage(text) {
    const canvas = document.createElement('canvas');
    canvas.width = 320;
    canvas.height = 200;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#e2e8f0';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#334155';
    ctx.font = '16px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(text, canvas.width / 2, canvas.height / 2);
    return new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
}

/**
 * 创建宿主桥。
 * @param {object} enabled 域开关：{ media: true, album: false, ... }；缺省全部开启。
 * @param {object} hooks   宿主侧观察点（示例页跳转、关闭容器等），由视图注入。
 */
export function createHostBridge(enabled = {}, hooks = {}) {
    const isOn = (id) => enabled[id] !== false;

    /** 组装某域：关闭时不放行该域对象（SDK 视为「未实现」→ 浏览器兜底）。 */
    const wrap = (id, methods) => {
        if (!isOn(id)) return undefined;
        const domain = {};
        for (const [name, fn] of Object.entries(methods)) {
            domain[name] = (...args) => {
                logBridgeCall({ domain: id, method: name, path: 'native', detail: '宿主已实现 → 原生路径' });
                return fn(...args);
            };
        }
        return domain;
    };

    const bridge = {
        // 1. media —— 同意告知书之前的红线由 SDK 保证（§3.6）；宿主仅在被调用时取流。
        media: wrap('media', {
            acquire(constraints = {}) {
                // 模拟「宿主原生取流」：真实渠道在这里调 App 的原生摄像头通道。
                const wanted = constraints.video !== false;
                if (!wanted) return Promise.resolve(new MediaStream());
                return navigator.mediaDevices.getUserMedia({ video: true, audio: constraints.audio !== false });
            },
            release(stream) {
                if (stream && typeof stream.getTracks === 'function') {
                    stream.getTracks().forEach((track) => track.stop());
                }
                return Promise.resolve();
            },
        }),

        // 2. album —— 真实渠道调系统相册；mock 直接产出一张占位图。
        album: wrap('album', {
            pick() {
                return placeholderImage('渠道相册选图（宿主 mock）');
            },
        }),

        // 3. ocr —— 真实渠道调自有 OCR SDK。
        ocr: wrap('ocr', {
            recognize(image, kind) {
                return Promise.resolve({
                    name: '张三',
                    idNo: '110101199001011234',
                    cardNo: kind === 'BANK_CARD' ? '6222021234567890123' : undefined,
                    raw: { source: 'host-ocr-mock', kind, imageBytes: image ? image.size : 0 },
                });
            },
        }),

        // 4. face —— 真实渠道调自有核身服务。
        face: wrap('face', {
            verify(image, options = {}) {
                const threshold = options.threshold || 0.9;
                return Promise.resolve({
                    score: 0.97,
                    passed: true,
                    threshold,
                    auditId: 'host-face-' + Date.now(),
                    imageBytes: image ? image.size : 0,
                });
            },
        }),

        // 5. signature —— 真实自助设备走物理签字板；浏览器 demo 用 canvas 手写返回。
        signature: wrap('signature', {
            open(options = {}) {
                return new Promise((resolve) => {
                    const canvas = document.createElement('canvas');
                    canvas.width = 480;
                    canvas.height = 200;
                    const ctx = canvas.getContext('2d');
                    ctx.fillStyle = '#ffffff';
                    ctx.fillRect(0, 0, canvas.width, canvas.height);
                    ctx.strokeStyle = '#1d4ed8';
                    ctx.lineWidth = 3;
                    ctx.beginPath();
                    ctx.moveTo(60, 130);
                    ctx.quadraticCurveTo(140, 40, 220, 120);
                    ctx.quadraticCurveTo(300, 190, 420, 70);
                    ctx.stroke();
                    ctx.fillStyle = '#64748b';
                    ctx.font = '14px sans-serif';
                    ctx.fillText(options.title || '宿主签字板 mock', 16, 24);
                    canvas.toBlob((blob) => resolve(blob), 'image/png');
                });
            },
        }),

        // 6. record —— 真实渠道走自有落盘；mock 返回回执，fileSize 如实反映分片大小。
        record: wrap('record', {
            append(chunk, meta) {
                return Promise.resolve({
                    fileSize: chunk ? chunk.size : 0,
                    fileHash: 'host-mock-' + (meta && meta.segmentIndex),
                    fileUrl: undefined,
                });
            },
        }),

        // 7. biometrics —— 未实现时 SDK 不做降级替代（不臆造认证结果，contract.md §7）。
        biometrics: wrap('biometrics', {
            auth() {
                return Promise.resolve({ passed: true, method: 'host-fingerprint' });
            },
        }),

        // 8. device —— 自助外设（§6.2）：身份证阅读器 / 凭条打印机。
        device: wrap('device', {
            readIdCard() {
                return Promise.resolve({ name: '张三', idNo: '110101199001011234', validUntil: '2035-01-01' });
            },
            printReceipt(payload) {
                return Promise.resolve({ accepted: true, printerId: 'kiosk-01', ticket: payload ? String(payload.title || '').slice(0, 40) : '' });
            },
        }),

        // 9. navigate —— 渠道方保留自己的路由（§6.1：SDK 不拥有页面）。
        navigate: wrap('navigate', {
            close() {
                if (typeof hooks.onClose === 'function') hooks.onClose();
                return Promise.resolve();
            },
            to(target, params) {
                if (typeof hooks.onNavigate === 'function') hooks.onNavigate(target, params);
                return Promise.resolve();
            },
        }),

        // 10. telemetry —— 渠道方自有埋点；示例页写入本地日志。
        telemetry: wrap('telemetry', {
            log(event, payload) {
                logBridgeCall({ domain: 'telemetry', method: 'log', path: 'native', detail: event });
                if (typeof hooks.onTelemetry === 'function') hooks.onTelemetry(event, payload);
                return Promise.resolve();
            },
        }),

        // 可选域（contract.md §10）：渠道票据与后端地址。
        network: wrap('network', {
            token() {
                return Promise.resolve('demo-channel-token-' + Date.now());
            },
            baseUrl() {
                return Promise.resolve('');
            },
        }),
    };

    // 关闭的域直接删除键：SDK 侧 contract 读到 undefined 即走 Web 兜底。
    for (const [id, value] of Object.entries(bridge)) {
        if (value === undefined) delete bridge[id];
    }
    return bridge;
}
