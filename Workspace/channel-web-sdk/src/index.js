/*
 * 渠道 SDK 公共 API（契约 §1 / §6.1）。
 *
 *   import { mount } from 'vb-banking';
 *   const sdk = mount('#container', { apiBase, channelId, tenantId });
 *   await sdk.startBusiness();
 *   sdk.destroy();
 *
 * UMD 全局：window.VideoBanking（lead 裁定的「UMD 全局名唯一性」；控件为 VideoBankingWidget）。
 * SDK 只拥有挂载容器，不接管宿主路由（§1）。
 */
import './styles/vb-banking.css';
import './styles/hologram.css';
import './styles/evaluation.css';
import './styles/node-dialog.css';

import { mount, getActive } from './app/instance.js';
import { configure } from './core/sdk.js';
import { installBridge } from './bridge/index.js';
import { registry } from './core/registry.js';
import { bus, EVENTS } from './core/bus.js';
import { quality } from './core/quality.js';
import { identity } from './core/identity.js';
import { trace } from './core/trace.js';
import { probeCapabilities } from './core/capabilities.js';
import { video, configure as configureVideoWidget } from './core/video-widget.js';
import * as format from './core/format.js';

export const version = '1.0.0';

/** 在已挂载实例上启动业务（等价于 mount() 返回值的 startBusiness()）。 */
export function startBusiness() {
    const instance = getActive();
    if (!instance) throw new Error('尚未挂载 SDK 实例，请先调用 mount(el, config)。');
    return instance.startBusiness();
}

/** 销毁当前实例（等价于 mount() 返回值的 destroy()）。 */
export function destroy() {
    const instance = getActive();
    if (!instance) return Promise.resolve();
    return instance.destroy();
}

export {
    mount,
    configure,          // SDK 级配置（apiBase 等）
    installBridge,      // 宿主原生桥注入（十域契约见 src/bridge/contract.md）
    registry,           // 场景注册表（import.meta.glob 自动收集）
    bus,
    EVENTS,             // 内核事件常量
    quality,            // 视频质量档 configure/get/degrade
    identity,           // 端身份 ensure/ensureBizNo/reset
    trace,              // 操作留痕 id/record/flush/pendingCount
    probeCapabilities,  // §8.2 运行环境能力探测
    video,              // §2.9.3 视频控件指令适配
    configureVideoWidget,
    format,             // escape/money/安全记录地址等工具
};

export default {
    version,
    mount,
    startBusiness,
    destroy,
    configure,
    installBridge,
    registry,
    bus,
    EVENTS,
    quality,
    identity,
    trace,
    probeCapabilities,
    video,
    configureVideoWidget,
    format,
};
