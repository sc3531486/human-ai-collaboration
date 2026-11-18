/*
 * 宿主能力开关（共享状态）：「宿主能力」页逐域开关，
 * 「远程视频银行」页挂载时按当前开关组装桥对象。
 * 演示「宿主已实现能力走原生、未实现走浏览器兜底」两条路径的替换方式。
 */
import { reactive } from 'vue';
import { DOMAINS, createHostBridge } from './bridge-host.js';

/** 缺省：渠道方多数能力已实现；face/ocr 关闭用于演示「未实现 → 浏览器兜底」。 */
export const capabilityState = reactive({
    enabled: {
        media: true,
        album: true,
        ocr: false,
        face: false,
        signature: true,
        record: true,
        biometrics: true,
        device: true,
        navigate: true,
        telemetry: true,
        network: true,
    },
});

export { DOMAINS };

export function toggle(id, value) {
    capabilityState.enabled[id] = value;
}

/** 按当前开关组装桥；hooks 由视图注入（navigate.close → 卸载容器等）。 */
export function buildBridge(hooks = {}) {
    return createHostBridge(capabilityState.enabled, hooks);
}
