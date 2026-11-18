/**
 * 传输注册表 —— 换厂商的唯一开关。
 *
 * 业务代码只依赖 `MediaTransport` 的接口；厂商实现在这里登记后按名取用。
 * 约定：**业务文件里不得出现任何厂商字样**（agora / trtc / zego / peerjs …），
 * 需要换厂商时只改这里的一行 `registerTransport(...)` 与默认名，业务侧零改动。
 *
 * 另提供 `injectStream(stream)`：渠道方（手机银行 / 网银 / 自助设备）已有自己的视频能力时，
 * 直接把现成的 MediaStream 注入进来，控件照常录制、抓帧、合成，不重复申请权限、不重新建链。
 */

import { MediaTransport, TransportState, TransportEvent } from './MediaTransport.js';

/** @type {Map<string, new (options?:object) => MediaTransport>} */
const adapters = new Map();

/** 默认实现名。`setDefaultTransport()` 可改，未登记时保持 undefined 由调用方显式指定。 */
let defaultName = null;

/**
 * 登记一个传输实现。
 * @param {string} name 逻辑名（如 'p2p' / 'vendor'），**不要带厂商名**
 * @param {new (options?:object) => MediaTransport} adapter 构造子类
 */
export function registerTransport(name, adapter) {
    if (!name) throw new Error('registerTransport 需要实现名');
    if (typeof adapter !== 'function') throw new Error(`传输实现 ${name} 必须是构造函数`);
    adapters.set(name, adapter);
    if (!defaultName) defaultName = name;
    return name;
}

export function unregisterTransport(name) {
    const removed = adapters.delete(name);
    if (removed && defaultName === name) defaultName = [...adapters.keys()][0] ?? null;
    return removed;
}

export function setDefaultTransport(name) {
    if (!adapters.has(name)) throw new Error(`未登记的传输实现：${name}`);
    defaultName = name;
    return defaultName;
}

export function getDefaultTransportName() { return defaultName; }

export function listTransports() { return [...adapters.keys()]; }

export function hasTransport(name) { return adapters.has(name); }

/**
 * 创建传输实例。
 * @param {string} [name] 省略时用默认实现
 * @param {object} [options] 透传给实现构造
 */
export function createTransport(name = defaultName, options = {}) {
    if (!name) throw new Error('尚未登记任何传输实现，请先 registerTransport(...)');
    const Adapter = adapters.get(name);
    if (!Adapter) throw new Error(`未登记的传输实现：${name}（已登记：${listTransports().join(', ') || '无'}）`);
    // 把逻辑名透传给实现：实例的 name（日志/质量上报用）= 注册时的逻辑名，
    // 而不是基类的 'abstract'。显式 options.name 仍优先。
    return new Adapter({ ...options, name: options.name ?? name });
}

/**
 * 渠道方已有视频能力时的注入式传输。
 *
 * 它不建链、不申请权限：`publish()` 收到的就是外部已有的流，`subscribe()` 直接返回注入的远端流。
 * 这样录制 / 抓帧 / 三分屏合成这些能力可以原样复用，不需要为 SDK 渠道再写一套。
 */
export class InjectedTransport extends MediaTransport {
    constructor(options = {}) {
        super(options);
        this.name = 'injected';
        this.localStream = null;
        /** @type {Map<string, MediaStream>} */
        this.remoteStreams = new Map();
    }

    async connect(_options = {}) {
        this.setState(TransportState.CONNECTED, { injected: true });
        return this;
    }

    async publish(stream) {
        this.localStream = stream || null;
        this._counters.publish += 1;
        if (this.localStream) this.emit(TransportEvent.LOCAL, { stream: this.localStream });
        return this.localStream;
    }

    async subscribe(peerId = 'injected') {
        const stream = this.remoteStreams.get(peerId) || null;
        this._counters.subscribe += 1;
        if (stream) this.emit(TransportEvent.REMOTE, { stream, peerId });
        return stream;
    }

    /** 注入远端流：渠道方拿到画面后调用，控件侧表现为一次正常的 remote 事件。 */
    injectStream(stream, peerId = 'injected') {
        if (!stream) throw new Error('injectStream 需要有效的 MediaStream');
        this.remoteStreams.set(peerId, stream);
        this._counters.subscribe += 1;
        this.emit(TransportEvent.REMOTE, { stream, peerId });
        return peerId;
    }

    /** 移除注入的远端流（对端离开或渠道回收）。 */
    removeStream(peerId = 'injected') {
        const existed = this.remoteStreams.delete(peerId);
        if (existed) this.emit(TransportEvent.PEER_LEFT, { peerId });
        return existed;
    }

    async hangup(_peerId) {
        this._counters.hangup += 1;
        [...this.remoteStreams.keys()].forEach(peerId => {
            this.remoteStreams.delete(peerId);
            this.emit(TransportEvent.PEER_LEFT, { peerId });
        });
        this.setState(TransportState.CLOSED);
        return true;
    }

    getStats() {
        return {
            ...super.getStats(),
            remotes: this.remoteStreams.size,
            hasLocal: Boolean(this.localStream)
        };
    }
}

/**
 * 便捷入口：把外部已有的流直接灌进一个传输实例。
 * @param {MediaStream} stream
 * @param {{peerId?:string, transport?:MediaTransport}} [options] 不传 transport 时新建一个注入式实例
 */
export function injectStream(stream, options = {}) {
    const transport = options.transport ?? new InjectedTransport(options);
    // 注入型传输要先处于已连接状态，否则上层状态机会把 remote 事件当成"未建链就收到画面"而丢弃。
    if (transport.state !== TransportState.CONNECTED) transport.setState(TransportState.CONNECTED, { injected: true });
    transport.injectStream(stream, options.peerId ?? 'injected');
    return transport;
}

/** 测试与页面重载时用：清空登记，避免单测之间互相污染。 */
export function resetTransports() {
    adapters.clear();
    defaultName = null;
}

/** 注册 P2P 之外的默认实现用不到 PeerJS，因此这里不 import P2PTransport，保持注册表零依赖。 */
export default {
    registerTransport,
    unregisterTransport,
    setDefaultTransport,
    getDefaultTransportName,
    listTransports,
    hasTransport,
    createTransport,
    injectStream,
    resetTransports,
    InjectedTransport
};
