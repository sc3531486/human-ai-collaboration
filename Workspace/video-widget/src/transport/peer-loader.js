/**
 * PeerJS 构造函数装载器。
 *
 * ★ 之所以不直接 `import Peer from 'peerjs'`：
 *   - 内核必须能在 node 里跑单测（node 里没有浏览器 bundle）；
 *   - 宿主项目未声明 peerjs 依赖时，静态 import 会让 **构建直接失败**。
 *
 * 取用顺序：globalThis.Peer（页面加载的 peerjs）→ 宿主注入的 setPeerFactory(...) → null（明确报错）。
 * 需要静态打包的宿主可在自己的入口里一行注入：`setPeerFactory(() => import('peerjs').then(m => m.default))`。
 */

/**
 * @returns {Function|null} PeerJS 构造函数；取不到时返回 null（由 P2PTransport 抛出明确错误）
 */
export function loadPeer() {
    if (typeof globalThis.Peer === 'function') return globalThis.Peer;
    return null;
}

/** 宿主项目显式注入：`setPeerFactory(() => import('peerjs').then(m => m.default))` */
export function setPeerFactory(factory) {
    globalThis.__bankPeerFactory = factory;
    return factory;
}

export default loadPeer;
