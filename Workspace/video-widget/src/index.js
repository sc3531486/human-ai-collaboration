/**
 * 远程视频银行 · 独立视频控件 —— ★ 唯一公开入口（契约 §2.9）。
 *
 * 交付形态（§2.9 / §8.1）：
 *   - dist/vb-video.esm.js  —— ESM（agent-web 这类有打包器的宿主直接 import）
 *   - dist/vb-video.umd.js  —— UMD，全局名 `window.VideoBankingWidget`（§8.2 全局名唯一性：channel-web-sdk 的
 *                              vb-banking.umd.js 用 window.VideoBanking，两份同页共存互不覆盖）
 *   - dist/vb-video.css     —— 控件样式（.vb- 前缀，不污染宿主）
 *
 * 公开面（§2.9「index.js 唯一公开入口：mount / destroy / version」）：
 *   mount(container, options) → stage     渲染视图进容器
 *   destroy(stage?)                      卸载（缺省卸载全部）
 *   version                              构建版本号
 *   video / default                      命令 API 单例（§2.9.3：open/close/connect/hangup/
 *                                        takeover/toggle/capture/record.start/record.seal/on/getState）
 *
 * ★ 零 TypeScript、零 Vue：视图层是原生 JS（stage.js），内核不碰 DOM。
 *   业务侧**只 import 本文件**，不得下钻 src/**（契约红线）。
 */

import './styles.css';

import './transport/index.js'; // 副作用：登记 p2p / injected 两个逻辑传输名并设默认（§6.4）

import { video, createVideoController, MODE, TAKEOVER, CONNECTION, layout } from './controller.js';
import { VideoStage } from './stage.js';
import { MediaTransport, TransportState, TransportEvent } from './transport/MediaTransport.js';
import {
    createTransport, registerTransport, unregisterTransport, resetTransports,
    setDefaultTransport, getDefaultTransportName, listTransports, hasTransport,
    injectStream, InjectedTransport
} from './transport/registry.js';
import { ChunkQueue, SegmentRecorder, makeRecordingId } from './media/recorder.js';

export const version = '1.0.0';
export { version as VERSION };

/** 本模块挂过的 stage：destroy() 无参时批量卸载。 */
const mounted = new Set();

/**
 * 把控件视图渲染进宿主容器。
 *
 * 与命令 API 解耦：可以先 mount 再 video.open(...)，也可以只用命令 API 不 mount
 * （无 UI 场景由宿主自建承载面 controller.useSurface(...)）。
 *
 * @param {Element} container 浏览器容器元素
 * @param {{controller?:object, toolbar?:boolean, showLocalPreview?:boolean, zIndex?:number,
 *          icons?:object, placeholder?:string, onCapture?:Function, onHangup?:Function}} [options]
 * @returns {VideoStage} stage，带 stage.destroy()
 */
export function mount(container, options = {}) {
    const controller = options.controller ?? video;
    const stage = new VideoStage({ ...options, controller });
    stage.mount(container);
    mounted.add(stage);
    return stage;
}

/**
 * 卸载视图。
 * @param {VideoStage} [stage] 缺省时卸载本模块挂过的全部 stage
 */
export function destroy(stage) {
    if (stage) {
        stage.destroy?.();
        mounted.delete(stage);
        return true;
    }
    [...mounted].forEach(item => item.destroy?.());
    mounted.clear();
    return true;
}

export {
    // 命令 API（§2.9.3）
    video, createVideoController, MODE, TAKEOVER, CONNECTION, layout,
    // 视图层（宿主也可自己 new）
    VideoStage,
    // 传输抽象与注册表（换厂商只改登记行，§6.4）
    MediaTransport, TransportState, TransportEvent,
    createTransport, registerTransport, unregisterTransport, resetTransports,
    setDefaultTransport, getDefaultTransportName, listTransports, hasTransport,
    injectStream, InjectedTransport,
    // 录制内核（重载补传等高级场景可直接使用，常规走 video.record.*）
    ChunkQueue, SegmentRecorder, makeRecordingId
};

export default video;
