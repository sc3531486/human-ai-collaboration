/*
 * 宿主能力调用日志：记录每一次能力调用走了哪条路径——
 *   native   宿主已实现，走原生（渠道方已有能力，不重写）
 *   browser  宿主未实现，走浏览器/SDK 兜底（契约 §6.2：未实现不阻断业务）
 * 仅用于示例页展示与演示，不参与业务。
 */
import { reactive } from 'vue';

export const bridgeLog = reactive({
    entries: [],
});

let seq = 0;

export function logBridgeCall({ domain, method, path, detail = '' }) {
    bridgeLog.entries.unshift({
        id: ++seq,
        at: new Date().toLocaleTimeString(),
        domain,
        method,
        path, // 'native' | 'browser'
        detail,
    });
    if (bridgeLog.entries.length > 200) bridgeLog.entries.length = 200;
}

export function clearBridgeLog() {
    bridgeLog.entries.length = 0;
    seq = 0;
}
