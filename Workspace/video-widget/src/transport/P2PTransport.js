/**
 * P2P 参考实现（PeerJS）—— 默认传输，原型阶段使用。
 *
 * ★ peer-id 沿用 navis-web 现有约定：`bank-<agentId>`（坐席）/ `cust-*`（客户），
 *   换厂商时只要新实现沿用同一份 id 语义，服务端与业务侧无需改动。
 *
 * ★ 本文件**不 import peerjs**。PeerJS 构造函数由外部注入：
 *   - `options.peerFactory`（宿主项目 `import Peer from 'peerjs'` 后注入），或
 *   - `registerPeerFactory()` 全局登记，或
 *   - `globalThis.Peer`（兜底）。
 *   这样内核在 node 里可单测，且宿主项目未装 peerjs 时 **构建不会挂**。
 *
 * 行为对齐 `navis-web/public/js/agent_signaling.js`：
 *  - 只接听白名单内的对端：基线里 `incoming.peer !== mobile_peer_id` 直接 close，
 *    否则任意一个无关来电都会顶掉当前通话。
 *  - 本地流复用：end() 后轨道可能仍存活，复用时必须重新派发 local，否则预览挂不回去。
 *  - 建链超时 12s 后明确报错，而不是一直停在"连接中"。
 */

import { MediaTransport, TransportState, TransportEvent } from './MediaTransport.js';

const CONNECT_TIMEOUT = 12000;
// 客户需要阅读并确认音视频告知书，不能用数秒超时把正常授权误判为失败。
const PEER_READY_RETRY_LIMIT = 40;
const PEER_READY_RETRY_DELAY = 800;

/** 默认 ICE：沿用基线，内网部署时通常还要补一个自建 TURN。 */
export const DEFAULT_ICE_SERVERS = [
    { urls: 'stun:stun.cloudflare.com:3478' }
];

export class P2PTransport extends MediaTransport {
    constructor(options = {}) {
        super(options);
        this.name = options.name || 'p2p';
        this.peerId = options.peerId || null;
        this.iceServers = options.iceServers || DEFAULT_ICE_SERVERS;
        /** 白名单：为空表示**不接听任何来电**（与基线一致：没有绑定对端就不接）。 */
        this._allowed = new Set(options.allowFrom ? [].concat(options.allowFrom) : []);
        this.peer = null;
        this.localStream = null;
        /** @type {Map<string, object>} peerId -> PeerJS MediaConnection */
        this.calls = new Map();
        /** @type {Map<string, MediaStream>} */
        this.remoteStreams = new Map();
        this._opening = null;
        this._handlers = [];
        /** 对端收到服务端入房信令、完成授权前，外呼短暂重试而非立刻报失败。 */
        this._retryTimers = new Map();
        this._pendingIncoming = new Map();
    }

    /**
     * 允许接听的对端。业务侧拿到 `mobile_peer_id` 后调用；
     * 未调用前任何来电都被拒，避免抢线。
     */
    allowFrom(peerId) {
        [].concat(peerId).filter(Boolean).forEach(id => this._allowed.add(id));
        return [...this._allowed];
    }

    denyFrom(peerId) {
        [].concat(peerId).forEach(id => this._allowed.delete(id));
        return [...this._allowed];
    }

    get allowedPeers() { return [...this._allowed]; }

    /** 解析 PeerJS 构造函数：注入优先，其次全局登记，最后 globalThis 兜底。 */
    async resolvePeerCtor() {
        if (typeof this.options.peerFactory === 'function') return this.options.peerFactory();
        if (typeof this.options.Peer === 'function') return this.options.Peer;
        if (typeof globalThis.Peer === 'function') return globalThis.Peer;
        const registered = globalThis.__bankPeerFactory;
        if (typeof registered === 'function') return registered();
        throw new Error('未找到 PeerJS 实现：请在宿主项目中注入 peerFactory，或安装 peerjs 依赖');
    }

    /**
     * 建链（**幂等**）：创建 Peer 并等 open。
     *
     * controller 会在 publish 前驱动一次，subscribe 的自举还会再调一次（W1 接线）——
     * 进行中复用同一 promise，已建链直接返回，**绝不重复 new Peer**。
     *
     * @param {{peerId?:string, processId?:string, timeout?:number,
     *          iceServers?:Array, localPeerId?:string}} [options]
     *   `peerId`/`processId` 是 **对端**上下文（controller 透传，本实现不据此改本地标识）；
     *   本地 peer-id 只来自构造 `options.peerId`，或显式 `localPeerId` 兜底——
     *   ★ 绝不能把 controller 传来的对端 peerId 当本地 id 注册（会与对端抢同一个 id）。
     */
    async connect(options = {}) {
        if (this.peerId === null && options.localPeerId) this.peerId = options.localPeerId;
        if (options.iceServers) this.iceServers = options.iceServers;
        // 幂等 guard 1：建链进行中 → 复用同一 promise，重复调用不再 new Peer。
        if (this._opening) return this._opening;
        // 幂等 guard 2：已建链 → 直接返回现有 peer。
        if (this.peer && this._state === TransportState.CONNECTED) return this.peer;

        const PeerCtor = await this.resolvePeerCtor();
        // 幂等 guard 1 复查：resolvePeerCtor 是 await 边界；从这里到 _opening 赋值全程同步，
        // 并发的第二个 connect 在此直接复用同一 promise，不会 new 出第二个 Peer。
        if (this._opening) return this._opening;
        this.setState(TransportState.CONNECTING, { peerId: this.peerId });
        this._counters.connect += 1;

        this._opening = new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                reject(new Error('信令连接超时，可重试连接'));
            }, options.timeout ?? CONNECT_TIMEOUT);

            const peer = new PeerCtor(this.peerId ?? undefined, {
                config: { iceServers: this.iceServers }
            });
            this.peer = peer;

            peer.on('open', id => {
                clearTimeout(timeout);
                this.peerId = id || this.peerId;
                this.setState(TransportState.CONNECTED, { peerId: this.peerId });
                resolve(peer);
            });

            peer.on('error', error => {
                clearTimeout(timeout);
                const wrapped = new Error(`音视频连接失败：${error?.type || '网络异常'}`);
                // open 之前的错误是致命的（链路没起来）；之后多是单通会话级错误，只上报。
                if (this._state === TransportState.CONNECTING) {
                    this.setState(TransportState.ERROR, { error: wrapped.message });
                    reject(wrapped);
                } else {
                    this._counters.errors += 1;
                    this.emit(TransportEvent.ERROR, { error: wrapped, fatal: false });
                }
            });

            peer.on('disconnected', () => {
                this.setState(TransportState.RECONNECTING);
                try { peer.reconnect?.(); } catch (error) { this.emit(TransportEvent.ERROR, { error, fatal: false }); }
            });

            peer.on('call', incoming => this._onIncoming(incoming));

            peer.on('close', () => {
                if (this._state !== TransportState.CLOSED) this.setState(TransportState.CLOSED);
            });
        });

        try { return await this._opening; }
        catch (error) {
            // 失败后把 peer 清掉，下次 connect 能重新建链而不是复用一个坏实例。
            try { this.peer?.destroy?.(); } catch (destroyError) { /* 忽略 */ }
            this.peer = null;
            // ★ 建链超时（或信令失败）也必须落 ERROR：只 reject 不迁移状态，
            //   state 会停在 connecting，界面永远显示"连接中"且调用方不知该重试。
            if (this._state === TransportState.CONNECTING || this._state === TransportState.RECONNECTING) {
                this.setState(TransportState.ERROR, { error: error?.message ?? String(error) });
            }
            this._counters.errors += 1;
            this.emit(TransportEvent.ERROR, { error, fatal: true });
            throw error;
        }
        finally { this._opening = null; }
    }

    /** 来电裁决：白名单 + 宿主可选的 shouldAccept。不满足直接 close，不静默挂起。 */
    _onIncoming(incoming) {
        const from = incoming?.peer;
        const allowed = this._allowed.has(from);
        const accepted = allowed && (typeof this.options.shouldAccept === 'function'
            ? this.options.shouldAccept(incoming) !== false
            : true);
        if (!accepted) {
            try { incoming.close(); } catch (error) { /* 拒接也要释放 */ }
            return;
        }
        if (!this.localStream) {
            // 入房信令已经绑定对端，但客户还在授权摄像头：保留邀请，授权后再 answer。
            this._pendingIncoming.get(from)?.close();
            this._pendingIncoming.set(from, incoming);
            incoming.on('close', () => {
                if (this._pendingIncoming.get(from) === incoming) this._pendingIncoming.delete(from);
            });
            return;
        }
        try {
            // 同一时刻只保留一通：新来电顶掉旧通话，避免两路远端流混在一个窗口里。
            // 同一房间允许多个对端；只替换同一对端的旧连接，不踢掉客户或办理坐席。
            const previous = this.calls.get(from);
            if (previous) { try { previous.close(); } catch (error) { /* 旧连接释放 */ } }
            this._attach(incoming);
            incoming.answer(this.localStream ?? null);
        } catch (error) {
            try { incoming.close(); } catch (closeError) { /* 忽略 */ }
            this.emit(TransportEvent.ERROR, { error, fatal: false });
        }
    }

    /** 绑定一通会话的 stream / close / error 回调。 */
    _attach(call, outbound = null) {
        this.calls.set(call.peer, call);

        call.on('stream', stream => {
            // 只认当前会话：旧会话的迟到回调不能覆盖新画面。
            if (this.calls.get(call.peer) !== call) return;
            this.remoteStreams.set(call.peer, stream);
            this.emit(TransportEvent.REMOTE, { stream, peerId: call.peer });
        });

        call.on('close', () => {
            if (this.calls.get(call.peer) !== call) return;
            this.calls.delete(call.peer);
            this.remoteStreams.delete(call.peer);
            this.emit(TransportEvent.PEER_LEFT, { peerId: call.peer });
        });

        call.on('error', error => {
            if (this.calls.get(call.peer) !== call) return;
            this._counters.errors += 1;
            const unavailable = error?.type === 'peer-unavailable'
                || error?.type === 'unavailable-id'
                || /unavailable/i.test(error?.message || '');
            if (outbound && unavailable && outbound.attempt < PEER_READY_RETRY_LIMIT) {
                // media-svc 已下发入房信令，但客户仍可能在授权或注册 Peer 的短窗口中。
                // 保持业务已接听状态，等待客户就绪后重拨；期间不向坐席端抛红色失败提示。
                this.calls.delete(call.peer);
                this.remoteStreams.delete(call.peer);
                try { call.close(); } catch { /* PeerJS 已失败时 close 可忽略 */ }
                clearTimeout(this._retryTimers.get(call.peer));
                const timer = setTimeout(() => {
                    this._retryTimers.delete(call.peer);
                    if (this._state === TransportState.CONNECTED && !this.calls.has(call.peer)) {
                        this._dial(call.peer, outbound.options, outbound.attempt + 1);
                    }
                }, PEER_READY_RETRY_DELAY);
                this._retryTimers.set(call.peer, timer);
                return;
            }
            this.emit(TransportEvent.ERROR, { error, fatal: false, peerId: call.peer });
        });
    }

    _dial(peerId, options = {}, attempt = 0) {
        if (!this.peer) return null;
        const call = this.peer.call(peerId, this.localStream ?? null, options);
        if (!call) return null;
        this._attach(call, { options, attempt });
        return call;
    }

    /**
     * 发布本地流。
     * 已建链时尽力把轨道替换进现有连接（replaceTrack），避免为了换设备整通重连。
     */
    async publish(stream) {
        this.localStream = stream || null;
        this._counters.publish += 1;
        if (this.localStream) this.emit(TransportEvent.LOCAL, { stream: this.localStream });
        if (this.localStream) {
            const waiting = [...this._pendingIncoming.values()];
            this._pendingIncoming.clear();
            waiting.forEach(call => this._onIncoming(call));
        }
        await this._replaceTracks();
        return this.localStream;
    }

    async _replaceTracks() {
        const stream = this.localStream;
        if (!stream) return false;
        for (const call of this.calls.values()) {
            const connection = call.peerConnection;
            if (!connection?.getSenders) return false;
            try {
                for (const sender of connection.getSenders()) {
                    const kind = sender.track?.kind;
                    if (!kind) continue;
                    const next = stream.getTracks().find(track => track.kind === kind);
                    if (next && sender.replaceTrack) await sender.replaceTrack(next);
                }
            } catch (error) {
                // 换轨失败不是致命错误：维持原有轨道继续通话，交由上层决定是否重连。
                this.emit(TransportEvent.ERROR, { error, fatal: false });
                return false;
            }
        }
        return true;
    }

    /**
     * 订阅（外呼）一个对端。
     * @param {string} peerId
     * @param {{metadata?:object}} [options]
     */
    async subscribe(peerId, options = {}) {
        if (!peerId) throw new Error('订阅目标 peerId 不能为空');
        // 自举补链（幂等）：controller 路径已连过，这里直接返回；独立使用 transport 的
        // 调用方（不经 controller）仍能在 subscribe 前把信令建起来。
        await this.connect();
        this._counters.subscribe += 1;
        const existing = this.calls.get(peerId);
        if (existing) return existing;
        const call = this._dial(peerId, options);
        if (!call) throw new Error('呼叫未能发起，请检查对端是否在线');
        return call;
    }

    /** 挂断：关所有会话与 Peer，回到 CLOSED。 */
    async hangup(peerId) {
        this._counters.hangup += 1;
        [...this._pendingIncoming.entries()].forEach(([id, call]) => {
            if (!peerId || id === peerId) { call.close(); this._pendingIncoming.delete(id); }
        });
        const targets = peerId ? [peerId] : [...this.calls.keys()];
        (peerId ? [peerId] : [...this._retryTimers.keys()]).forEach(id => {
            clearTimeout(this._retryTimers.get(id));
            this._retryTimers.delete(id);
        });
        targets.forEach(id => {
            const call = this.calls.get(id);
            try { call?.close(); } catch (error) { /* 忽略 */ }
            this.calls.delete(id);
            this.remoteStreams.delete(id);
            if (!peerId) this.emit(TransportEvent.PEER_LEFT, { peerId: id });
        });
        if (!peerId) {
            try { this.peer?.destroy?.(); } catch (error) { /* 忽略 */ }
            this.peer = null;
            this.remoteStreams.clear();
            this.setState(TransportState.CLOSED);
        }
        return true;
    }

    /**
     * 质量数据。PeerJS 暴露 peerConnection 时取真实统计；
     * 取不到就退化为计数器——**统计失败绝不能影响通话**。
     */
    getStats() {
        const base = super.getStats();
        const call = [...this.calls.values()][0];
        const connection = call?.peerConnection;
        if (!connection?.getStats) {
            return { ...base, calls: this.calls.size, remotes: this.remoteStreams.size, peerId: this.peerId };
        }
        return {
            ...base,
            calls: this.calls.size,
            remotes: this.remoteStreams.size,
            peerId: this.peerId,
            // 异步采样不阻塞 getStats 的同步契约，调用方需要明细时再 await。
            sample: () => connection.getStats().then(report => {
                const result = { bytesReceived: 0, bytesSent: 0, packetsLost: 0, roundTripTime: null };
                report.forEach?.(row => {
                    if (row.type === 'inbound-rtp') {
                        result.bytesReceived += Number(row.bytesReceived) || 0;
                        result.packetsLost += Number(row.packetsLost) || 0;
                    }
                    if (row.type === 'outbound-rtp') result.bytesSent += Number(row.bytesSent) || 0;
                    if (row.type === 'candidate-pair' && row.currentRoundTripTime != null) {
                        result.roundTripTime = Number(row.currentRoundTripTime);
                    }
                });
                return result;
            }).catch(() => null)
        };
    }
}

export default P2PTransport;
