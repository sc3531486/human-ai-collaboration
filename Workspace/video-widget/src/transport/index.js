/**
 * 传输层出口：登记默认实现，业务侧只从这里取接口。
 *
 * ★ 业务代码**只依赖 `MediaTransport` 的接口**，不出现任何厂商字样。
 *   换厂商时改这里的一行 `registerTransport('vendor', XxxTransport)` + `setDefaultTransport('vendor')`。
 */

import { MediaTransport, TransportState, TransportEvent } from './MediaTransport.js';
import { P2PTransport, DEFAULT_ICE_SERVERS } from './P2PTransport.js';
import { registerTransport, setDefaultTransport, createTransport, injectStream, InjectedTransport } from './registry.js';
import { loadPeer, setPeerFactory } from './peer-loader.js';

/** 默认实现名。逻辑名，不含厂商字样。 */
export const DEFAULT_TRANSPORT = 'p2p';

registerTransport(DEFAULT_TRANSPORT, P2PTransport);
registerTransport('injected', InjectedTransport);
setDefaultTransport(DEFAULT_TRANSPORT);

/**
 * 建一个默认传输实例。
 * @param {{peerId?:string, role?:'agent'|'customer', allowFrom?:string[],
 *          iceServers?:Array, peerFactory?:Function}} [options]
 */
export function createDefaultTransport(options = {}) {
    return createTransport(DEFAULT_TRANSPORT, {
        // 注入而不是 import：peerjs 未安装时本行不会导致构建失败。
        peerFactory: options.peerFactory ?? loadPeer,
        ...options
    });
}

export {
    MediaTransport,
    TransportState,
    TransportEvent,
    P2PTransport,
    InjectedTransport,
    DEFAULT_ICE_SERVERS,
    registerTransport,
    setDefaultTransport,
    createTransport,
    injectStream,
    loadPeer,
    setPeerFactory
};

export default { createDefaultTransport, MediaTransport, TransportState, TransportEvent };
