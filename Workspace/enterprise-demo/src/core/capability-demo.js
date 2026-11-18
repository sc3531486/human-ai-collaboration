/*
 * 能力自检：以「SDK 视角」逐域调用一次——
 * 桥中实现的走宿主原生，缺省的走浏览器兜底，两条路径都写入调用日志。
 * 与 SDK 的实际降级顺序对齐（channel-web-sdk/src/bridge/contract.md），
 * 用于在不挂载 SDK 的情况下验证宿主能力配置。
 */
import { buildBridge } from './bridge-state.js';
import { logBridgeCall } from './bridge-log.js';

/** 与 contract.md 一致的兜底实现（缺省即「浏览器路径」）。 */
const FALLBACKS = {
    media: async () => {
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
            stream.getTracks().forEach((t) => t.stop());
            return 'MediaStream(已立即释放)';
        } catch {
            return 'getUserMedia 不可用（WebView 限制），交由宿主或提示';
        }
    },
    album: () => 'web: <input type="file" accept="image/*">',
    ocr: () => 'backend: 后端能力接口识别（SDK 不阻断）',
    face: () => 'backend: 后端核身能力判定',
    signature: () => 'web: SDK Canvas 手写板',
    record: () => 'web: IndexedDB 分片队列 + /api/balance/record/stream-chunk',
    biometrics: () => 'skip: 未实现则跳过，不臆造认证结果',
    device: () => 'none: 无外设兜底，业务继续',
    navigate: () => 'none: SDK 不主动跳转，办结仅展示结果',
    telemetry: () => 'drop: 埋点丢弃，不影响业务',
    network: () => 'default: 同源默认地址',
};

/** 各域的演示入参（不产生副作用的最小调用）；域缺省时由调用方直接走兜底，不进入 probe。 */
const PROBES = {
    media: (bridge) => (bridge.media.acquire ? bridge.media.acquire({ video: false, audio: false }).then((s) => {
        if (s && s.getTracks) s.getTracks().forEach((t) => t.stop());
        return 'MediaStream OK';
    }) : null),
    album: (bridge) => (bridge.album.pick ? bridge.album.pick().then((b) => `Blob(${b.size})`) : null),
    ocr: (bridge) => (bridge.ocr.recognize ? bridge.ocr.recognize(null, 'ID_FRONT').then((r) => JSON.stringify(r)) : null),
    face: (bridge) => (bridge.face.verify ? bridge.face.verify(null).then((r) => JSON.stringify(r)) : null),
    signature: (bridge) => (bridge.signature.open ? bridge.signature.open({ title: '自检' }).then((b) => `Blob(${b.size})`) : null),
    record: (bridge) => (bridge.record.append ? bridge.record.append(new Blob(['x']), {
        bizNo: 'DEMO001', processId: 'p-1', segmentIndex: 1,
        handlerType: 'DIGITAL_HUMAN', handlerName: '晨曦', nodesCovered: '', isLast: false,
    }).then((r) => JSON.stringify(r)) : null),
    biometrics: (bridge) => (bridge.biometrics.auth ? bridge.biometrics.auth('能力自检').then((r) => JSON.stringify(r)) : null),
    device: (bridge) => (bridge.device.readIdCard ? bridge.device.readIdCard().then((r) => JSON.stringify(r)) : null),
    navigate: (bridge) => (bridge.navigate.to ? bridge.navigate.to('home', { from: 'self-check' }).then(() => 'navigate.to(home) 已交宿主处理（自检不实际跳转）') : null),
    telemetry: (bridge) => (bridge.telemetry.log ? bridge.telemetry.log('self_check', { at: Date.now() }).then(() => '已上报宿主埋点') : null),
    network: (bridge) => (bridge.network.token ? bridge.network.token() : null),
};

/** navigate 自检不真的跳转：临时摘掉 onNavigate 钩子。 */
export async function runCapabilityDemo() {
    const bridge = buildBridge({ onNavigate: () => {} });
    for (const [domain, probe] of Object.entries(PROBES)) {
        // 域缺省（宿主开关关闭）→ 与 SDK 相同的降级：直接走浏览器兜底，不触碰宿主方法。
        if (!bridge[domain]) {
            const fallback = FALLBACKS[domain];
            const detail = fallback ? await fallback() : '无兜底';
            logBridgeCall({ domain, method: 'probe', path: 'browser', detail: '宿主未实现 → ' + detail });
            continue;
        }
        try {
            const detail = await probe(bridge);
            if (detail === null || detail === undefined) {
                const fallback = FALLBACKS[domain];
                const fbDetail = fallback ? await fallback() : '无兜底';
                logBridgeCall({ domain, method: 'probe', path: 'browser', detail: '宿主缺该方法 → ' + fbDetail });
                continue;
            }
            logBridgeCall({ domain, method: 'probe', path: 'native', detail: String(detail) });
        } catch (error) {
            logBridgeCall({ domain, method: 'probe', path: 'browser', detail: '宿主实现抛错 → 按未实现降级：' + error.message });
        }
    }
}
