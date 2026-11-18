/**
 * 传输层抽象基类 —— ★ 纯 JS、框架无关、不含任何 UI，可在 node 里直接单测。
 *
 * 存在的理由：原型阶段用 P2P（PeerJS），实际产品要集成音视频厂商（声网 / 腾讯 / 即构…）。
 * 把"信令 + 建链 + 收流"收敛到一个接口后，换厂商只需要 `registerTransport(name, Adapter)`，
 * 业务代码一行不改，也**不会在任何业务文件里出现厂商字样**。
 *
 * 接口（所有实现必须提供）：
 *   connect(opts)            建链，返回 Promise
 *   publish(stream)          发布本地流（可为空，表示只收不发）
 *   subscribe(peerId)        订阅/外呼某个对端，拿到远端流
 *   hangup()                 挂断并释放
 *   getStats()               观测数据（质量、计时、错误计数）
 *   on(event, cb)            订阅事件，返回取消订阅函数
 *
 * 事件契约（统一，不因实现而变）：
 *   state    { state, previous, detail }  连接状态迁移
 *   local    { stream }                   本地流就绪/变更
 *   remote   { stream, peerId }           远端流到达
 *   peer-left{ peerId }                   对端离开
 *   error    { error, fatal }             错误；fatal=true 表示链路已不可用
 *   stats    { ... }                      质量采样
 */

export const TransportState = Object.freeze({
    IDLE: 'idle',
    CONNECTING: 'connecting',
    CONNECTED: 'connected',
    RECONNECTING: 'reconnecting',
    DISCONNECTED: 'disconnected',
    CLOSED: 'closed',
    ERROR: 'error'
});

export const TransportEvent = Object.freeze({
    STATE: 'state',
    LOCAL: 'local',
    REMOTE: 'remote',
    PEER_LEFT: 'peer-left',
    ERROR: 'error',
    STATS: 'stats'
});

export class MediaTransport {
    /**
     * @param {{peerId?:string, role?:'agent'|'customer', shouldAccept?:(incoming:object)=>boolean}} options
     */
    constructor(options = {}) {
        this.options = options;
        this.role = options.role || 'agent';
        /** @type {string} 实现名，供日志与质量上报区分；业务侧不应对它做分支。 */
        // 注册表 createTransport 会把**逻辑名**（如 'vendor'）透传进来；
        // 直接 new 的实例没有 name 时退化为 'abstract'。
        this.name = options.name ?? 'abstract';
        this._state = TransportState.IDLE;
        this._listeners = new Map();
        this._since = 0;
        this._counters = { connect: 0, publish: 0, subscribe: 0, hangup: 0, errors: 0 };
    }

    // ---------- 事件 ----------

    /** @returns {() => void} 取消订阅函数，便于在组件卸载里直接调用。 */
    on(event, handler) {
        if (typeof handler !== 'function') return () => {};
        if (!this._listeners.has(event)) this._listeners.set(event, new Set());
        this._listeners.get(event).add(handler);
        return () => this.off(event, handler);
    }

    off(event, handler) {
        this._listeners.get(event)?.delete(handler);
    }

    emit(event, payload) {
        const handlers = this._listeners.get(event);
        if (!handlers) return;
        // 复制一份再遍历：订阅者可能在回调里取消订阅，直接遍历 Set 会漏掉后续订阅者。
        for (const handler of [...handlers]) {
            try { handler(payload); }
            catch (error) { console.error(`[transport] ${this.name} 事件 ${event} 订阅者异常`, error); }
        }
    }

    // ---------- 状态 ----------

    get state() { return this._state; }

    /**
     * 状态迁移。相同状态不重复派发——视图层订阅 state 刷新时，
     * 重复事件会造成无意义的重渲染与"连接中"闪烁。
     */
    setState(next, detail = null) {
        if (!Object.values(TransportState).includes(next)) throw new Error(`未知的传输状态：${next}`);
        if (this._state === next) return this._state;
        const previous = this._state;
        this._state = next;
        if (next === TransportState.CONNECTED && !this._since) this._since = Date.now();
        if (next === TransportState.CLOSED || next === TransportState.IDLE) this._since = 0;
        this.emit(TransportEvent.STATE, { state: next, previous, detail, transport: this.name });
        return next;
    }

    get connectedAt() { return this._since; }

    /** 通话时长（毫秒）。未连接时为 0，便于视图层直接拿来渲染计时器。 */
    get duration() { return this._since ? Date.now() - this._since : 0; }

    // ---------- 接口（子类实现） ----------

    /**
     * 建链。**由 controller 在 publish 之前统一驱动**（W1 接线）；实现可能在同一通里
     * 被调用第二次（实现内部自举，如 P2P 的 subscribe 补链），因此**必须幂等**：
     * 建链进行中复用进行中的 promise，已建链直接返回，不得重复建 Peer/信令。
     *
     * @param {{peerId?:string, processId?:string, timeout?:number}} [options]
     *   `peerId` = **对端** id（可空：坐席本地见证，不订阅任何远端），
     *   `processId` = 流程上下文（供实现做日志/信令元数据）。本地标识一律走构造 options。
     * @throws {Error} 基类直接抛错——接口必须被实现，静默返回会让调用方误以为建链成功。
     */
    async connect(_options = {}) { throw new Error(`${this.name} 传输未实现 connect()`); }

    async publish(_stream) { throw new Error(`${this.name} 传输未实现 publish()`); }

    async subscribe(_peerId) { throw new Error(`${this.name} 传输未实现 subscribe()`); }

    async hangup() { throw new Error(`${this.name} 传输未实现 hangup()`); }

    /**
     * 观测数据。**必须永远返回对象**，不得抛错——质量上报失败不该打断通话。
     */
    getStats() {
        return {
            name: this.name,
            state: this._state,
            role: this.role,
            duration: this.duration,
            ...this._counters
        };
    }

    /** 释放。实现里应把所有事件订阅者也清掉，避免组件已卸载还收到回调。 */
    async destroy() {
        if (this._state !== TransportState.CLOSED) {
            try { await this.hangup(); } catch (error) { /* 释放路径不再抛，避免掩盖真正的清理错误 */ }
        }
        this._listeners.clear();
        return true;
    }
}

export default MediaTransport;
