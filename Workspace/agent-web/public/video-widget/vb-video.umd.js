(function(global, factory) {
	typeof exports === "object" && typeof module !== "undefined" ? factory(exports) : typeof define === "function" && define.amd ? define(["exports"], factory) : (global = typeof globalThis !== "undefined" ? globalThis : global || self, factory(global.VideoBankingWidget = {}));
})(this, function(exports) {
	Object.defineProperties(exports, {
		__esModule: { value: true },
		[Symbol.toStringTag]: { value: "Module" }
	});
	//#region \0rolldown/runtime.js
	var __defProp = Object.defineProperty;
	var __exportAll = (all, no_symbols) => {
		let target = {};
		for (var name in all) __defProp(target, name, {
			get: all[name],
			enumerable: true
		});
		if (!no_symbols) __defProp(target, Symbol.toStringTag, { value: "Module" });
		return target;
	};
	//#endregion
	//#region src/transport/MediaTransport.js
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
	var TransportState = Object.freeze({
		IDLE: "idle",
		CONNECTING: "connecting",
		CONNECTED: "connected",
		RECONNECTING: "reconnecting",
		DISCONNECTED: "disconnected",
		CLOSED: "closed",
		ERROR: "error"
	});
	var TransportEvent = Object.freeze({
		STATE: "state",
		LOCAL: "local",
		REMOTE: "remote",
		PEER_LEFT: "peer-left",
		ERROR: "error",
		STATS: "stats"
	});
	var MediaTransport = class {
		/**
		* @param {{peerId?:string, role?:'agent'|'customer', shouldAccept?:(incoming:object)=>boolean}} options
		*/
		constructor(options = {}) {
			var _options$name;
			this.options = options;
			this.role = options.role || "agent";
			/** @type {string} 实现名，供日志与质量上报区分；业务侧不应对它做分支。 */
			this.name = (_options$name = options.name) !== null && _options$name !== void 0 ? _options$name : "abstract";
			this._state = TransportState.IDLE;
			this._listeners = /* @__PURE__ */ new Map();
			this._since = 0;
			this._counters = {
				connect: 0,
				publish: 0,
				subscribe: 0,
				hangup: 0,
				errors: 0
			};
		}
		/** @returns {() => void} 取消订阅函数，便于在组件卸载里直接调用。 */
		on(event, handler) {
			if (typeof handler !== "function") return () => {};
			if (!this._listeners.has(event)) this._listeners.set(event, /* @__PURE__ */ new Set());
			this._listeners.get(event).add(handler);
			return () => this.off(event, handler);
		}
		off(event, handler) {
			var _this$_listeners$get;
			(_this$_listeners$get = this._listeners.get(event)) === null || _this$_listeners$get === void 0 || _this$_listeners$get.delete(handler);
		}
		emit(event, payload) {
			const handlers = this._listeners.get(event);
			if (!handlers) return;
			for (const handler of [...handlers]) try {
				handler(payload);
			} catch (error) {
				console.error(`[transport] ${this.name} 事件 ${event} 订阅者异常`, error);
			}
		}
		get state() {
			return this._state;
		}
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
			this.emit(TransportEvent.STATE, {
				state: next,
				previous,
				detail,
				transport: this.name
			});
			return next;
		}
		get connectedAt() {
			return this._since;
		}
		/** 通话时长（毫秒）。未连接时为 0，便于视图层直接拿来渲染计时器。 */
		get duration() {
			return this._since ? Date.now() - this._since : 0;
		}
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
		async connect(_options = {}) {
			throw new Error(`${this.name} 传输未实现 connect()`);
		}
		async publish(_stream) {
			throw new Error(`${this.name} 传输未实现 publish()`);
		}
		async subscribe(_peerId) {
			throw new Error(`${this.name} 传输未实现 subscribe()`);
		}
		async hangup() {
			throw new Error(`${this.name} 传输未实现 hangup()`);
		}
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
			if (this._state !== TransportState.CLOSED) try {
				await this.hangup();
			} catch (error) {}
			this._listeners.clear();
			return true;
		}
	};
	//#endregion
	//#region src/transport/P2PTransport.js
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
	var CONNECT_TIMEOUT = 12e3;
	var PEER_READY_RETRY_LIMIT = 40;
	var PEER_READY_RETRY_DELAY = 800;
	/** 默认 ICE：沿用基线，内网部署时通常还要补一个自建 TURN。 */
	var DEFAULT_ICE_SERVERS = [{ urls: "stun:stun.cloudflare.com:3478" }];
	var P2PTransport = class extends MediaTransport {
		constructor(options = {}) {
			super(options);
			this.name = options.name || "p2p";
			this.peerId = options.peerId || null;
			this.iceServers = options.iceServers || DEFAULT_ICE_SERVERS;
			/** 白名单：为空表示**不接听任何来电**（与基线一致：没有绑定对端就不接）。 */
			this._allowed = new Set(options.allowFrom ? [].concat(options.allowFrom) : []);
			this.peer = null;
			this.localStream = null;
			/** @type {Map<string, object>} peerId -> PeerJS MediaConnection */
			this.calls = /* @__PURE__ */ new Map();
			/** @type {Map<string, MediaStream>} */
			this.remoteStreams = /* @__PURE__ */ new Map();
			this._opening = null;
			this._handlers = [];
			/** 对端收到服务端入房信令、完成授权前，外呼短暂重试而非立刻报失败。 */
			this._retryTimers = /* @__PURE__ */ new Map();
			this._pendingIncoming = /* @__PURE__ */ new Map();
		}
		/**
		* 允许接听的对端。业务侧拿到 `mobile_peer_id` 后调用；
		* 未调用前任何来电都被拒，避免抢线。
		*/
		allowFrom(peerId) {
			[].concat(peerId).filter(Boolean).forEach((id) => this._allowed.add(id));
			return [...this._allowed];
		}
		denyFrom(peerId) {
			[].concat(peerId).forEach((id) => this._allowed.delete(id));
			return [...this._allowed];
		}
		get allowedPeers() {
			return [...this._allowed];
		}
		/** 解析 PeerJS 构造函数：注入优先，其次全局登记，最后 globalThis 兜底。 */
		async resolvePeerCtor() {
			if (typeof this.options.peerFactory === "function") return this.options.peerFactory();
			if (typeof this.options.Peer === "function") return this.options.Peer;
			if (typeof globalThis.Peer === "function") return globalThis.Peer;
			const registered = globalThis.__bankPeerFactory;
			if (typeof registered === "function") return registered();
			throw new Error("未找到 PeerJS 实现：请在宿主项目中注入 peerFactory，或安装 peerjs 依赖");
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
			if (this._opening) return this._opening;
			if (this.peer && this._state === TransportState.CONNECTED) return this.peer;
			const PeerCtor = await this.resolvePeerCtor();
			if (this._opening) return this._opening;
			this.setState(TransportState.CONNECTING, { peerId: this.peerId });
			this._counters.connect += 1;
			this._opening = new Promise((resolve, reject) => {
				var _options$timeout, _this$peerId;
				const timeout = setTimeout(() => {
					reject(/* @__PURE__ */ new Error("信令连接超时，可重试连接"));
				}, (_options$timeout = options.timeout) !== null && _options$timeout !== void 0 ? _options$timeout : CONNECT_TIMEOUT);
				const peer = new PeerCtor((_this$peerId = this.peerId) !== null && _this$peerId !== void 0 ? _this$peerId : void 0, { config: { iceServers: this.iceServers } });
				this.peer = peer;
				peer.on("open", (id) => {
					clearTimeout(timeout);
					this.peerId = id || this.peerId;
					this.setState(TransportState.CONNECTED, { peerId: this.peerId });
					resolve(peer);
				});
				peer.on("error", (error) => {
					clearTimeout(timeout);
					const wrapped = /* @__PURE__ */ new Error(`音视频连接失败：${(error === null || error === void 0 ? void 0 : error.type) || "网络异常"}`);
					if (this._state === TransportState.CONNECTING) {
						this.setState(TransportState.ERROR, { error: wrapped.message });
						reject(wrapped);
					} else {
						this._counters.errors += 1;
						this.emit(TransportEvent.ERROR, {
							error: wrapped,
							fatal: false
						});
					}
				});
				peer.on("disconnected", () => {
					this.setState(TransportState.RECONNECTING);
					try {
						var _peer$reconnect;
						(_peer$reconnect = peer.reconnect) === null || _peer$reconnect === void 0 || _peer$reconnect.call(peer);
					} catch (error) {
						this.emit(TransportEvent.ERROR, {
							error,
							fatal: false
						});
					}
				});
				peer.on("call", (incoming) => this._onIncoming(incoming));
				peer.on("close", () => {
					if (this._state !== TransportState.CLOSED) this.setState(TransportState.CLOSED);
				});
			});
			try {
				return await this._opening;
			} catch (error) {
				try {
					var _this$peer, _this$peer$destroy;
					(_this$peer = this.peer) === null || _this$peer === void 0 || (_this$peer$destroy = _this$peer.destroy) === null || _this$peer$destroy === void 0 || _this$peer$destroy.call(_this$peer);
				} catch (destroyError) {}
				this.peer = null;
				if (this._state === TransportState.CONNECTING || this._state === TransportState.RECONNECTING) {
					var _error$message;
					this.setState(TransportState.ERROR, { error: (_error$message = error === null || error === void 0 ? void 0 : error.message) !== null && _error$message !== void 0 ? _error$message : String(error) });
				}
				this._counters.errors += 1;
				this.emit(TransportEvent.ERROR, {
					error,
					fatal: true
				});
				throw error;
			} finally {
				this._opening = null;
			}
		}
		/** 来电裁决：白名单 + 宿主可选的 shouldAccept。不满足直接 close，不静默挂起。 */
		_onIncoming(incoming) {
			const from = incoming === null || incoming === void 0 ? void 0 : incoming.peer;
			if (!(this._allowed.has(from) && (typeof this.options.shouldAccept === "function" ? this.options.shouldAccept(incoming) !== false : true))) {
				try {
					incoming.close();
				} catch (error) {}
				return;
			}
			if (!this.localStream) {
				var _this$_pendingIncomin;
				(_this$_pendingIncomin = this._pendingIncoming.get(from)) === null || _this$_pendingIncomin === void 0 || _this$_pendingIncomin.close();
				this._pendingIncoming.set(from, incoming);
				incoming.on("close", () => {
					if (this._pendingIncoming.get(from) === incoming) this._pendingIncoming.delete(from);
				});
				return;
			}
			try {
				var _this$localStream;
				const previous = this.calls.get(from);
				if (previous) try {
					previous.close();
				} catch (error) {}
				this._attach(incoming);
				incoming.answer((_this$localStream = this.localStream) !== null && _this$localStream !== void 0 ? _this$localStream : null);
			} catch (error) {
				try {
					incoming.close();
				} catch (closeError) {}
				this.emit(TransportEvent.ERROR, {
					error,
					fatal: false
				});
			}
		}
		/** 绑定一通会话的 stream / close / error 回调。 */
		_attach(call, outbound = null) {
			this.calls.set(call.peer, call);
			call.on("stream", (stream) => {
				if (this.calls.get(call.peer) !== call) return;
				this.remoteStreams.set(call.peer, stream);
				this.emit(TransportEvent.REMOTE, {
					stream,
					peerId: call.peer
				});
			});
			call.on("close", () => {
				if (this.calls.get(call.peer) !== call) return;
				this.calls.delete(call.peer);
				this.remoteStreams.delete(call.peer);
				this.emit(TransportEvent.PEER_LEFT, { peerId: call.peer });
			});
			call.on("error", (error) => {
				if (this.calls.get(call.peer) !== call) return;
				this._counters.errors += 1;
				const unavailable = (error === null || error === void 0 ? void 0 : error.type) === "peer-unavailable" || (error === null || error === void 0 ? void 0 : error.type) === "unavailable-id" || /unavailable/i.test((error === null || error === void 0 ? void 0 : error.message) || "");
				if (outbound && unavailable && outbound.attempt < PEER_READY_RETRY_LIMIT) {
					this.calls.delete(call.peer);
					this.remoteStreams.delete(call.peer);
					try {
						call.close();
					} catch (_unused) {}
					clearTimeout(this._retryTimers.get(call.peer));
					const timer = setTimeout(() => {
						this._retryTimers.delete(call.peer);
						if (this._state === TransportState.CONNECTED && !this.calls.has(call.peer)) this._dial(call.peer, outbound.options, outbound.attempt + 1);
					}, PEER_READY_RETRY_DELAY);
					this._retryTimers.set(call.peer, timer);
					return;
				}
				this.emit(TransportEvent.ERROR, {
					error,
					fatal: false,
					peerId: call.peer
				});
			});
		}
		_dial(peerId, options = {}, attempt = 0) {
			var _this$localStream2;
			if (!this.peer) return null;
			const call = this.peer.call(peerId, (_this$localStream2 = this.localStream) !== null && _this$localStream2 !== void 0 ? _this$localStream2 : null, options);
			if (!call) return null;
			this._attach(call, {
				options,
				attempt
			});
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
				waiting.forEach((call) => this._onIncoming(call));
			}
			await this._replaceTracks();
			return this.localStream;
		}
		async _replaceTracks() {
			const stream = this.localStream;
			if (!stream) return false;
			for (const call of this.calls.values()) {
				const connection = call.peerConnection;
				if (!(connection === null || connection === void 0 ? void 0 : connection.getSenders)) return false;
				try {
					for (const sender of connection.getSenders()) {
						var _sender$track;
						const kind = (_sender$track = sender.track) === null || _sender$track === void 0 ? void 0 : _sender$track.kind;
						if (!kind) continue;
						const next = stream.getTracks().find((track) => track.kind === kind);
						if (next && sender.replaceTrack) await sender.replaceTrack(next);
					}
				} catch (error) {
					this.emit(TransportEvent.ERROR, {
						error,
						fatal: false
					});
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
			if (!peerId) throw new Error("订阅目标 peerId 不能为空");
			await this.connect();
			this._counters.subscribe += 1;
			const existing = this.calls.get(peerId);
			if (existing) return existing;
			const call = this._dial(peerId, options);
			if (!call) throw new Error("呼叫未能发起，请检查对端是否在线");
			return call;
		}
		/** 挂断：关所有会话与 Peer，回到 CLOSED。 */
		async hangup(peerId) {
			this._counters.hangup += 1;
			[...this._pendingIncoming.entries()].forEach(([id, call]) => {
				if (!peerId || id === peerId) {
					call.close();
					this._pendingIncoming.delete(id);
				}
			});
			const targets = peerId ? [peerId] : [...this.calls.keys()];
			(peerId ? [peerId] : [...this._retryTimers.keys()]).forEach((id) => {
				clearTimeout(this._retryTimers.get(id));
				this._retryTimers.delete(id);
			});
			targets.forEach((id) => {
				const call = this.calls.get(id);
				try {
					call === null || call === void 0 || call.close();
				} catch (error) {}
				this.calls.delete(id);
				this.remoteStreams.delete(id);
				if (!peerId) this.emit(TransportEvent.PEER_LEFT, { peerId: id });
			});
			if (!peerId) {
				try {
					var _this$peer2, _this$peer2$destroy;
					(_this$peer2 = this.peer) === null || _this$peer2 === void 0 || (_this$peer2$destroy = _this$peer2.destroy) === null || _this$peer2$destroy === void 0 || _this$peer2$destroy.call(_this$peer2);
				} catch (error) {}
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
			const connection = call === null || call === void 0 ? void 0 : call.peerConnection;
			if (!(connection === null || connection === void 0 ? void 0 : connection.getStats)) return {
				...base,
				calls: this.calls.size,
				remotes: this.remoteStreams.size,
				peerId: this.peerId
			};
			return {
				...base,
				calls: this.calls.size,
				remotes: this.remoteStreams.size,
				peerId: this.peerId,
				sample: () => connection.getStats().then((report) => {
					var _report$forEach;
					const result = {
						bytesReceived: 0,
						bytesSent: 0,
						packetsLost: 0,
						roundTripTime: null
					};
					(_report$forEach = report.forEach) === null || _report$forEach === void 0 || _report$forEach.call(report, (row) => {
						if (row.type === "inbound-rtp") {
							result.bytesReceived += Number(row.bytesReceived) || 0;
							result.packetsLost += Number(row.packetsLost) || 0;
						}
						if (row.type === "outbound-rtp") result.bytesSent += Number(row.bytesSent) || 0;
						if (row.type === "candidate-pair" && row.currentRoundTripTime != null) result.roundTripTime = Number(row.currentRoundTripTime);
					});
					return result;
				}).catch(() => null)
			};
		}
	};
	//#endregion
	//#region src/transport/registry.js
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
	/** @type {Map<string, new (options?:object) => MediaTransport>} */
	var adapters = /* @__PURE__ */ new Map();
	/** 默认实现名。`setDefaultTransport()` 可改，未登记时保持 undefined 由调用方显式指定。 */
	var defaultName = null;
	/**
	* 登记一个传输实现。
	* @param {string} name 逻辑名（如 'p2p' / 'vendor'），**不要带厂商名**
	* @param {new (options?:object) => MediaTransport} adapter 构造子类
	*/
	function registerTransport(name, adapter) {
		if (!name) throw new Error("registerTransport 需要实现名");
		if (typeof adapter !== "function") throw new Error(`传输实现 ${name} 必须是构造函数`);
		adapters.set(name, adapter);
		if (!defaultName) defaultName = name;
		return name;
	}
	function unregisterTransport(name) {
		var _;
		const removed = adapters.delete(name);
		if (removed && defaultName === name) defaultName = (_ = [...adapters.keys()][0]) !== null && _ !== void 0 ? _ : null;
		return removed;
	}
	function setDefaultTransport(name) {
		if (!adapters.has(name)) throw new Error(`未登记的传输实现：${name}`);
		defaultName = name;
		return defaultName;
	}
	function getDefaultTransportName() {
		return defaultName;
	}
	function listTransports() {
		return [...adapters.keys()];
	}
	function hasTransport(name) {
		return adapters.has(name);
	}
	/**
	* 创建传输实例。
	* @param {string} [name] 省略时用默认实现
	* @param {object} [options] 透传给实现构造
	*/
	function createTransport(name = defaultName, options = {}) {
		var _options$name;
		if (!name) throw new Error("尚未登记任何传输实现，请先 registerTransport(...)");
		const Adapter = adapters.get(name);
		if (!Adapter) throw new Error(`未登记的传输实现：${name}（已登记：${listTransports().join(", ") || "无"}）`);
		return new Adapter({
			...options,
			name: (_options$name = options.name) !== null && _options$name !== void 0 ? _options$name : name
		});
	}
	/**
	* 渠道方已有视频能力时的注入式传输。
	*
	* 它不建链、不申请权限：`publish()` 收到的就是外部已有的流，`subscribe()` 直接返回注入的远端流。
	* 这样录制 / 抓帧 / 三分屏合成这些能力可以原样复用，不需要为 SDK 渠道再写一套。
	*/
	var InjectedTransport = class extends MediaTransport {
		constructor(options = {}) {
			super(options);
			this.name = "injected";
			this.localStream = null;
			/** @type {Map<string, MediaStream>} */
			this.remoteStreams = /* @__PURE__ */ new Map();
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
		async subscribe(peerId = "injected") {
			const stream = this.remoteStreams.get(peerId) || null;
			this._counters.subscribe += 1;
			if (stream) this.emit(TransportEvent.REMOTE, {
				stream,
				peerId
			});
			return stream;
		}
		/** 注入远端流：渠道方拿到画面后调用，控件侧表现为一次正常的 remote 事件。 */
		injectStream(stream, peerId = "injected") {
			if (!stream) throw new Error("injectStream 需要有效的 MediaStream");
			this.remoteStreams.set(peerId, stream);
			this._counters.subscribe += 1;
			this.emit(TransportEvent.REMOTE, {
				stream,
				peerId
			});
			return peerId;
		}
		/** 移除注入的远端流（对端离开或渠道回收）。 */
		removeStream(peerId = "injected") {
			const existed = this.remoteStreams.delete(peerId);
			if (existed) this.emit(TransportEvent.PEER_LEFT, { peerId });
			return existed;
		}
		async hangup(_peerId) {
			this._counters.hangup += 1;
			[...this.remoteStreams.keys()].forEach((peerId) => {
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
	};
	/**
	* 便捷入口：把外部已有的流直接灌进一个传输实例。
	* @param {MediaStream} stream
	* @param {{peerId?:string, transport?:MediaTransport}} [options] 不传 transport 时新建一个注入式实例
	*/
	function injectStream(stream, options = {}) {
		var _options$transport, _options$peerId;
		const transport = (_options$transport = options.transport) !== null && _options$transport !== void 0 ? _options$transport : new InjectedTransport(options);
		if (transport.state !== TransportState.CONNECTED) transport.setState(TransportState.CONNECTED, { injected: true });
		transport.injectStream(stream, (_options$peerId = options.peerId) !== null && _options$peerId !== void 0 ? _options$peerId : "injected");
		return transport;
	}
	/** 测试与页面重载时用：清空登记，避免单测之间互相污染。 */
	function resetTransports() {
		adapters.clear();
		defaultName = null;
	}
	registerTransport("p2p", P2PTransport);
	registerTransport("injected", InjectedTransport);
	setDefaultTransport("p2p");
	//#endregion
	//#region src/layout.js
	var layout_exports = /* @__PURE__ */ __exportAll({
		DEFAULT_DOCK_LAYOUT: () => DEFAULT_DOCK_LAYOUT,
		DEFAULT_LAYOUT: () => DEFAULT_LAYOUT,
		STORAGE_KEY: () => STORAGE_KEY,
		clamp: () => clamp,
		default: () => layout_default,
		mergeLayout: () => mergeLayout,
		normalize: () => normalize,
		persistLayout: () => persistLayout,
		ratio: () => ratio,
		resolve: () => resolve,
		resolveDock: () => resolveDock,
		resolveLayout: () => resolveLayout,
		restoreLayout: () => restoreLayout
	});
	/**
	* 视频控件位置配置解析：比例为主 + 像素可选 + 边界 clamp（契约 §2.9.2）。
	*
	* ★ 纯函数、零 DOM 依赖、零框架依赖：本文件是内核的一部分，直接在 node 里单测。
	*   视图层（stage.js）与业务侧都用同一份口径换算，避免两处算法漂移。
	*
	* 三条来源判据（逐条对应契约）：
	*  1. 比例为主：`layout` 里的 left/top/width/height 是相对容器的归一化 0~1 值，
	*     天然适配 1366×768 ~ 2560×1440，不需要为每种分辨率写断点。
	*  2. 像素可选：`pixel` 字段存在时覆盖对应维度的比例换算结果（需要精确对齐时用）。
	*  3. 边界 clamp：换算后必须钳制在容器内——任何配置都不允许把窗口推出视口。
	*
	* 拖拽落位回写的是**归一化值**而不是像素（normalize），换分辨率后仍在相对位置。
	*
	* @typedef {{left?:number, top?:number, width?:number, height?:number, right?:number, bottom?:number}} NormalizedLayout
	* @typedef {{left?:number, top?:number, width?:number, height?:number}} PixelOverride
	* @typedef {{width:number, height:number}} Viewport
	* @typedef {{left:number, top:number, width:number, height:number}} LayoutRect
	* @typedef {{order:number, heightRatio:number, height:number}} DockLayout
	*/
	/** overlay 模式默认布局：右上角客户画面窗（对齐 navis-web 坐席工作台的习惯位置）。 */
	var DEFAULT_LAYOUT = {
		left: .72,
		top: .05,
		width: .26,
		height: .3
	};
	/** dock 模式默认布局：流式布局里退化为「顺序 + 高度占比」。 */
	var DEFAULT_DOCK_LAYOUT = {
		order: 0,
		heightRatio: .3,
		height: 0
	};
	var STORAGE_KEY = "bank.video.layout.v1";
	var finite = (value, fallback = 0) => {
		const number = Number(value);
		return Number.isFinite(number) ? number : fallback;
	};
	/** 归一化值钳到 0~1：配置里出现越界比例时按边界处理，不让窗口漂到容器外。 */
	var ratio = (value) => Math.min(Math.max(finite(value), 0), 1);
	var round4 = (value) => Number(Math.min(Math.max(finite(value), 0), 1).toFixed(4));
	/**
	* 边界 clamp：把像素盒子钳制在容器内。
	*
	* 尺寸大于容器时优先保左上对齐并截断到容器尺寸——若按比例缩放，
	* 用户拖到边上再拖回来会「缩水」，且绝对定位窗口整体漂移到不可见区域更难排查。
	*/
	function clamp(box, viewport = {
		width: 0,
		height: 0
	}) {
		const width = Math.max(0, finite(viewport.width));
		const height = Math.max(0, finite(viewport.height));
		const size = (value, total) => Math.min(Math.max(finite(value), 0), total);
		const boxWidth = size(box.width, width);
		const boxHeight = size(box.height, height);
		const maxLeft = Math.max(0, width - boxWidth);
		const maxTop = Math.max(0, height - boxHeight);
		return {
			left: Math.min(Math.max(finite(box.left), 0), maxLeft),
			top: Math.min(Math.max(finite(box.top), 0), maxTop),
			width: boxWidth,
			height: boxHeight
		};
	}
	/**
	* 把归一化布局 + 可选像素覆盖换算成容器内的像素盒子。
	*
	* ★ pixel 覆盖同样要过 clamp：像素场是可选的精确对齐手段，不是绕过边界约束的口子。
	*/
	function resolveLayout(layout = {}, viewport = {
		width: 0,
		height: 0
	}, pixel = {}) {
		const width = Math.max(0, finite(viewport.width));
		const height = Math.max(0, finite(viewport.height));
		const boxWidth = pixel.width != null ? finite(pixel.width) : ratio(layout.width) * width;
		const boxHeight = pixel.height != null ? finite(pixel.height) : ratio(layout.height) * height;
		return clamp({
			left: pixel.left != null ? finite(pixel.left) : layout.left != null ? ratio(layout.left) * width : layout.right != null ? width - boxWidth - ratio(layout.right) * width : 0,
			top: pixel.top != null ? finite(pixel.top) : layout.top != null ? ratio(layout.top) * height : layout.bottom != null ? height - boxHeight - ratio(layout.bottom) * height : 0,
			width: boxWidth,
			height: boxHeight
		}, {
			width,
			height
		});
	}
	/**
	* dock 模式（参与流式布局）的布局退化：`{ order, heightRatio }`。
	*
	* dock 只关心「排第几」和「占多高」，横向由父容器流式决定，
	* 因此这里不返回 left/top/width——返回了也会被 flex 布局忽略。
	*/
	function resolveDock(layout = {}, viewport = {
		width: 0,
		height: 0
	}) {
		const height = Math.max(0, finite(viewport.height));
		const heightRatio = layout.heightRatio != null ? ratio(layout.heightRatio) : ratio(DEFAULT_DOCK_LAYOUT.heightRatio);
		return {
			order: Math.max(0, Math.round(finite(layout.order, DEFAULT_DOCK_LAYOUT.order))),
			heightRatio,
			height: Math.round(heightRatio * height)
		};
	}
	/** 按模式分派：dock 走退化布局，其余走 overlay 的像素换算。 */
	function resolve(mode, layout = {}, viewport = {
		width: 0,
		height: 0
	}, pixel = {}) {
		return mode === "dock" ? resolveDock(layout, viewport) : resolveLayout(layout, viewport, pixel);
	}
	/**
	* 拖动落位回写：存归一化值而非像素。
	* 坐席可能今天 1366×768、明天换 2560×1440，存像素会让窗口跑到一个完全不对的位置。
	*/
	function normalize(box, viewport = {
		width: 0,
		height: 0
	}) {
		const width = finite(viewport.width);
		const height = finite(viewport.height);
		return {
			left: width > 0 ? round4(box.left / width) : 0,
			top: height > 0 ? round4(box.top / height) : 0,
			width: width > 0 ? round4(box.width / width) : 0,
			height: height > 0 ? round4(box.height / height) : 0
		};
	}
	function safeSession() {
		try {
			var _globalThis$sessionSt;
			return (_globalThis$sessionSt = globalThis.sessionStorage) !== null && _globalThis$sessionSt !== void 0 ? _globalThis$sessionSt : null;
		} catch (error) {
			return null;
		}
	}
	/** 落位持久化。隐私模式下 setItem 会抛，这里退化为「不持久化」而不是让拖拽报错。 */
	function persistLayout(layout, options = {}) {
		var _options$storage, _options$key;
		const storage = (_options$storage = options.storage) !== null && _options$storage !== void 0 ? _options$storage : safeSession();
		const key = (_options$key = options.key) !== null && _options$key !== void 0 ? _options$key : STORAGE_KEY;
		try {
			storage === null || storage === void 0 || storage.setItem(key, JSON.stringify(layout));
		} catch (error) {}
		return layout;
	}
	/** 读回落位；无记录或解析失败时返回 fallback 的副本，绝不返回 null 让调用方自己去判空。 */
	function restoreLayout(fallback = {}, options = {}) {
		var _options$storage2, _options$key2;
		const storage = (_options$storage2 = options.storage) !== null && _options$storage2 !== void 0 ? _options$storage2 : safeSession();
		const key = (_options$key2 = options.key) !== null && _options$key2 !== void 0 ? _options$key2 : STORAGE_KEY;
		try {
			const raw = storage === null || storage === void 0 ? void 0 : storage.getItem(key);
			return raw ? {
				...fallback,
				...JSON.parse(raw)
			} : { ...fallback };
		} catch (error) {
			return { ...fallback };
		}
	}
	/** 多个布局来源合并：后写的覆盖先写的，undefined/null 不覆盖。 */
	function mergeLayout(base = {}, patch = {}) {
		const merged = { ...base };
		Object.entries(patch || {}).forEach(([key, value]) => {
			if (value !== void 0 && value !== null) merged[key] = value;
		});
		return merged;
	}
	var layout_default = {
		DEFAULT_LAYOUT,
		DEFAULT_DOCK_LAYOUT,
		STORAGE_KEY,
		ratio,
		clamp,
		resolve,
		resolveLayout,
		resolveDock,
		normalize,
		persistLayout,
		restoreLayout,
		mergeLayout
	};
	//#endregion
	//#region src/media/recorder.js
	/**
	* 录制内核：MediaRecorder + IndexedDB 分片队列（源：`bank-media.js` + `agent_evidence.js`）。
	*
	* ★ 纯 JS、框架无关。不引第三方录制库——空分片上报与 0 字节如实提示的语义必须自己掌控。
	*
	* 红线（逐条来自基线，不得简化）：
	*  1. **空分片同样上报**。用 `size > 0` 过滤会导致"录满 40 秒、落盘 0 字节却报存证成功"——
	*     服务端据此才能把「录到了但没数据」与「根本没录」区分开。
	*  2. **先落 IndexedDB 再顺序上传**；页面重载后按同一 `recordingId` 补传。
	*  3. **首片登记空分片**：断网重连时也能保留"本段已开始"的可靠顺序标识。
	*  4. `fileSize === 0` 的回执**如实返回**，由业务侧决定是否提示；本模块绝不宣告成功。
	*  5. **上传失败重试沿用同一 eventId**：重试读的是同一个 `[recordingId, chunkIndex]` 行，
	*     不新建编号——否则服务端会把一次重试当成两片，落盘顺序错乱。
	*
	* 与 navis 基线的对应关系：
	*  - `ChunkQueue` 对齐 `navis-web/public/js/bank-media.js`（落库 → flush 顺序上传 → 删行 → 失败保留行）。
	*  - `SegmentRecorder` 的分片链路对齐 `agent_evidence.js` 的 `uploadChain`：
	*    持续上报 fire-and-forget 但**串成一条链**，封存前 `await uploadChain` 等全部排空。
	*  - 已知差异：navis 的 `flush()` 空队列时返回 null；本实现回退到 `lastReceipt`（最近一次成功回执），
	*    否则封存时最后一片早已上传完的场景会把回执丢成 `fileSize: 0`（详见 README「行为对照」）。
	*/
	/** 分片上传的接口字段，顺序即 FormData 的追加顺序，与后端 stream-chunk 对齐。 */
	var CHUNK_FIELDS = [
		"bizNo",
		"processId",
		"segmentIndex",
		"handlerType",
		"handlerName",
		"duration",
		"nodesCovered",
		"isLast",
		"recordingId",
		"chunkIndex"
	];
	var DB_NAME = "bank-media-outbox";
	var STORE = "chunks";
	var DB_VERSION = 1;
	/**
	* @typedef {{key?:string, recordingId:string, chunkIndex:number, startedAt?:number,
	*            blob?:Blob, isLast?:boolean, [field:string]:unknown}} ChunkRow
	* @typedef {{success:boolean, fileSize:number, recordingId?:string|null,
	*            chunks?:number, duration?:number, message?:string, [field:string]:unknown}} SealReceipt
	*/
	/** 生成 recordingId：优先沿用宿主的链路 id，便于与留痕串起来。 */
	function makeRecordingId(prefix = "agent") {
		const random = Math.random().toString(16).slice(2, 8);
		return `${prefix}-${Date.now()}-${random}`;
	}
	/**
	* IndexedDB 分片队列（源：`bank-media.js`）。
	*
	* 做成类而不是模块级单例，是因为单测要注入 fake indexedDB，
	* 且将来同一页面可能同时存在坐席段与本地段两个录制。
	*/
	var ChunkQueue = class {
		constructor(options = {}) {
			var _ref, _options$indexedDB, _options$Blob;
			this.dbName = options.dbName || DB_NAME;
			this.upload = options.upload || null;
			this.idb = (_ref = (_options$indexedDB = options.indexedDB) !== null && _options$indexedDB !== void 0 ? _options$indexedDB : globalThis.indexedDB) !== null && _ref !== void 0 ? _ref : null;
			this.BlobCtor = (_options$Blob = options.Blob) !== null && _options$Blob !== void 0 ? _options$Blob : globalThis.Blob;
			this.db = null;
			this.flushing = null;
			this.lastError = null;
			this.retries = 0;
			/** 最近一次成功上传的回执：flush 无可传时回退返回，封存据此不丢最后一片的回执。 */
			this.lastReceipt = null;
		}
		async open() {
			if (this.db) return this.db;
			if (!this.idb) throw new Error("当前环境不支持 IndexedDB，录像分片无法本地暂存");
			this.db = await new Promise((resolve, reject) => {
				const request = this.idb.open(this.dbName, DB_VERSION);
				request.onupgradeneeded = () => {
					const db = request.result;
					if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: "key" });
				};
				request.onsuccess = () => resolve(request.result);
				request.onerror = () => reject(/* @__PURE__ */ new Error("无法保存本地录像分片，请保留页面并检查存储权限"));
			});
			return this.db;
		}
		/**
		* 把一次 IDB 事务包成 Promise。
		*
		* ★ 关键：`work()` 返回的是 **IDBRequest**，真正的数据在 `request.result` 上。
		*   早期版本直接 resolve 了这个 request 对象，于是 `getAll()` 拿到的是一个非数组的
		*   请求句柄，`rows.length` 恒为 undefined → **队列一次都没上传**，
		*   「空分片上报」与「0 字节不得宣告成功」两条红线形同未执行。
		*   这里统一按 request 解包 `.result`。
		*/
		async operation(mode, work) {
			const db = await this.open();
			return new Promise((resolve, reject) => {
				let request;
				const transaction = db.transaction(STORE, mode);
				try {
					request = work(transaction.objectStore(STORE));
				} catch (error) {
					reject(error instanceof Error ? error : new Error(String(error)));
					return;
				}
				transaction.oncomplete = () => {
					resolve(request && typeof request === "object" && "result" in request ? request.result : request);
				};
				transaction.onerror = () => reject(/* @__PURE__ */ new Error("录像待传记录未能保存"));
				transaction.onabort = () => reject(/* @__PURE__ */ new Error("录像待传记录未能保存"));
			});
		}
		/**
		* 顺序上传所有待传分片，返回**最后一片的回执**。
		*
		* 排序按 `startedAt` 再按 `chunkIndex`：重载补传时队列里可能混着上一段与新段的记录，
		* 只按 chunkIndex 排会把上一段的大序号排到新段前面。
		*
		* @param rethrow 为 false 时吞掉上传异常（用于 ondataavailable 的持续上报），
		*                异常时行仍留在库里，下次 flush 沿用同一 eventId 重试。
		* @returns {Promise<object|null>} 本轮最后一片的回执；本轮无片可传时回退到
		*          `lastReceipt`（最近一次成功回执），一次都没成功过才是 null。
		*/
		async flush(rethrow = true) {
			if (this.flushing) return this.flushing;
			this.flushing = (async () => {
				let receipt = null;
				for (let guard = 0; guard < 1e4; guard += 1) {
					var _receipt, _item$blob;
					const rows = await this.operation("readonly", (store) => store.getAll());
					if (!rows || !rows.length) return (_receipt = receipt) !== null && _receipt !== void 0 ? _receipt : this.lastReceipt;
					rows.sort((a, b) => (a.startedAt || 0) - (b.startedAt || 0) || (a.chunkIndex || 0) - (b.chunkIndex || 0));
					const item = rows[0];
					const form = new FormData();
					form.append("chunk", (_item$blob = item.blob) !== null && _item$blob !== void 0 ? _item$blob : new this.BlobCtor([]));
					CHUNK_FIELDS.forEach((name) => {
						var _item$name;
						return form.append(name, String((_item$name = item[name]) !== null && _item$name !== void 0 ? _item$name : ""));
					});
					try {
						receipt = await this.upload(form, item);
						this.lastReceipt = receipt;
						this.lastError = null;
					} catch (error) {
						this.retries += 1;
						this.lastError = error instanceof Error ? error : new Error(String(error));
						if (rethrow) throw this.lastError;
						return receipt;
					}
					await this.operation("readwrite", (store) => store.delete(item.key));
				}
				return receipt;
			})();
			try {
				return await this.flushing;
			} finally {
				this.flushing = null;
			}
		}
		/**
		* 入队一片并尝试上传。
		* ★ 不校验 size：空分片也要入队上报。
		*/
		async enqueue(item) {
			var _item$startedAt;
			const row = {
				...item,
				startedAt: (_item$startedAt = item.startedAt) !== null && _item$startedAt !== void 0 ? _item$startedAt : Date.now(),
				key: `${item.recordingId}:${item.chunkIndex}`
			};
			await this.operation("readwrite", (store) => store.put(row));
			const receipt = await this.flush(false);
			const remaining = await this.operation("readonly", (store) => store.getAll());
			return remaining && remaining.length ? this.flush(false) : receipt;
		}
		async pending() {
			return (await this.operation("readonly", (store) => store.getAll()) || []).slice().sort((a, b) => (a.startedAt || 0) - (b.startedAt || 0) || (a.chunkIndex || 0) - (b.chunkIndex || 0));
		}
		async clear() {
			await this.operation("readwrite", (store) => store.clear());
			return true;
		}
	};
	/**
	* 分段录制器：管 MediaRecorder 生命周期 + 分片入队 + 封存。
	*
	* 依赖全部注入（`MediaRecorder` / `Blob` / `queue` / `compose`），
	* 因此 node 单测可以用假实现跑完整条链路。
	*/
	var SegmentRecorder = class {
		constructor(options) {
			var _ref2, _options$MediaRecorde, _options$Blob2, _options$timeslice;
			this.queue = options.queue;
			this.MediaRecorderCtor = (_ref2 = (_options$MediaRecorde = options.MediaRecorder) !== null && _options$MediaRecorde !== void 0 ? _options$MediaRecorde : globalThis.MediaRecorder) !== null && _ref2 !== void 0 ? _ref2 : null;
			this.BlobCtor = (_options$Blob2 = options.Blob) !== null && _options$Blob2 !== void 0 ? _options$Blob2 : globalThis.Blob;
			this.mimeCandidates = options.mimeCandidates || ["video/webm;codecs=vp8,opus", "video/webm"];
			this.timeslice = (_options$timeslice = options.timeslice) !== null && _options$timeslice !== void 0 ? _options$timeslice : 1e3;
			this.recorder = null;
			this.recordingId = null;
			this.meta = null;
			this.startedAt = 0;
			this.chunkIndex = 0;
			this.chunks = [];
			this.closing = false;
			this.sealing = null;
			this.mimeType = null;
			this.reportedChunks = 0;
			this.emptyChunks = 0;
			this.onError = null;
			/**
			* 分片上报链（对齐 navis `agent_evidence.js` 的 uploadChain）：
			* fire-and-forget 不阻塞录制，但全部串在一条链上，封存与单测通过它等排空。
			*/
			this.uploadChain = Promise.resolve();
		}
		get active() {
			return Boolean(this.recorder && this.recorder.state === "recording");
		}
		/** 选一个浏览器支持的封装格式；都不支持时抛错而不是让 MediaRecorder 自己在构造时炸。 */
		pickMimeType() {
			var _this$MediaRecorderCt;
			const found = typeof ((_this$MediaRecorderCt = this.MediaRecorderCtor) === null || _this$MediaRecorderCt === void 0 ? void 0 : _this$MediaRecorderCt.isTypeSupported) === "function" ? this.mimeCandidates.find((type) => this.MediaRecorderCtor.isTypeSupported(type)) : this.mimeCandidates[0];
			if (!found) throw new Error("浏览器不支持当前录像格式");
			return found;
		}
		/**
		* 把一片分片挂到上报链尾部（fire-and-forget：录制不被网络拖住，
		* 分片已落库，失败由后续 flush 沿用同一编号补传）。
		*/
		_enqueue(item) {
			this.uploadChain = this.uploadChain.then(() => this.queue.enqueue(item)).catch((error) => {
				this.emitError(error);
			});
			return this.uploadChain;
		}
		/** 等所有已入队分片上报完毕（封存前与单测断言前使用）。 */
		async whenIdle() {
			await this.uploadChain;
			return true;
		}
		async start(stream, meta = {}) {
			var _ref3, _meta$nodesCovered;
			if (this.active) return this.recordingId;
			if (this.sealing) throw new Error("上一段录像尚未上传，请先完成上传重试");
			if (!stream) throw new Error("未提供可录制的媒体流");
			if (!this.MediaRecorderCtor) throw new Error("当前浏览器不支持录制，请使用支持 MediaRecorder 的浏览器");
			if (!this.queue) throw new Error("录像上传组件未加载，请刷新页面后重新接听");
			this.mimeType = this.pickMimeType();
			this.meta = {
				processId: meta.processId || "",
				bizNo: meta.bizNo || "",
				segmentIndex: Number(meta.segmentIndex) || 1,
				nodeName: meta.nodeName || "人工办理",
				handlerType: meta.handlerType || "HUMAN_AGENT",
				handlerName: meta.handlerName || "远程坐席",
				nodesCovered: (_ref3 = (_meta$nodesCovered = meta.nodesCovered) !== null && _meta$nodesCovered !== void 0 ? _meta$nodesCovered : meta.nodeName) !== null && _ref3 !== void 0 ? _ref3 : "人工办理"
			};
			this.recordingId = meta.recordingId || makeRecordingId(this.meta.handlerType === "DIGITAL_HUMAN" ? "cust" : "agent");
			this.chunkIndex = 0;
			this.chunks = [];
			this.closing = false;
			this.reportedChunks = 0;
			this.emptyChunks = 0;
			this.startedAt = Date.now();
			const enqueue = (blob, isLast) => {
				const item = {
					...this.meta,
					duration: Math.max(1, Math.round((Date.now() - this.startedAt) / 1e3)),
					recordingId: this.recordingId,
					chunkIndex: this.chunkIndex++,
					blob,
					isLast: Boolean(isLast),
					startedAt: Date.now()
				};
				if (!blob || blob.size === 0) this.emptyChunks += 1;
				this.reportedChunks += 1;
				this._enqueue(item);
			};
			this.recorder = new this.MediaRecorderCtor(stream, { mimeType: this.mimeType });
			this.recorder.ondataavailable = (event) => {
				if (!event.data) return;
				this.chunks.push(event.data);
				enqueue(event.data, this.closing);
			};
			this.recorder.onerror = () => {
				this.emitError(/* @__PURE__ */ new Error("录制过程异常，请保留页面并重试"));
			};
			this._enqueue({
				...this.meta,
				duration: 1,
				recordingId: this.recordingId,
				chunkIndex: this.chunkIndex++,
				blob: new this.BlobCtor([], { type: this.mimeType }),
				isLast: false,
				startedAt: Date.now()
			});
			await this.uploadChain;
			this.reportedChunks += 1;
			this.emptyChunks += 1;
			this.recorder.start(this.timeslice);
			return this.recordingId;
		}
		emitError(message, cause) {
			var _this$onError;
			const error = message instanceof Error ? message : new Error(String(message));
			if (cause !== void 0) error.cause = cause;
			(_this$onError = this.onError) === null || _this$onError === void 0 || _this$onError.call(this, error);
			return error;
		}
		/**
		* 封存：停录制 → 等上报链与队列排空 → 返回**最后一片的回执**。
		*
		* 回执含 `fileSize`。**fileSize === 0 时如实返回**，不加工、不补默认值、不宣告成功
		* （`success` 在 0 字节时固定落 false，§3.4 红线）；是否提示由业务侧决定。
		* 上传失败时这里**抛出**（与持续上报不同），让业务侧知道这一段没封存成功。
		*/
		async seal() {
			if (this.sealing) return this.sealing;
			if (!this.active && !this.recordingId) return {
				success: false,
				fileSize: 0,
				message: "当前没有进行中的录制"
			};
			this.sealing = (async () => {
				if (this.active) {
					const active = this.recorder;
					this.closing = true;
					await new Promise((resolve, reject) => {
						active.onstop = () => resolve();
						active.onerror = () => reject(/* @__PURE__ */ new Error("录像封存失败，请保留页面并重试"));
						try {
							active.stop();
						} catch (error) {
							reject(/* @__PURE__ */ new Error("录像封存失败，请保留页面并重试"));
						}
					});
					this.recorder = null;
				}
				await this.uploadChain;
				const receipt = await this.queue.flush(true);
				const result = {
					recordingId: this.recordingId,
					chunks: this.reportedChunks,
					duration: Math.max(1, Math.round((Date.now() - this.startedAt) / 1e3)),
					...receipt || {}
				};
				result.fileSize = Number(result.fileSize) || 0;
				result.success = Boolean(result.success) && result.fileSize > 0;
				if (result.fileSize === 0) result.message = result.message || "该段未采集到画面";
				this.recordingId = null;
				return result;
			})();
			try {
				return await this.sealing;
			} finally {
				this.sealing = null;
			}
		}
		/** 放弃本段：停录制但**不封存**（例如业务驳回后作废）。 */
		async abort() {
			if (this.active) {
				try {
					this.recorder.stop();
				} catch (error) {}
				this.recorder = null;
			}
			this.recordingId = null;
			this.chunks = [];
			return true;
		}
	};
	/**
	* 等比铺满目标区域后居中裁切（等价 CSS `object-fit: cover`）。
	*
	* @param {CanvasRenderingContext2D} context
	* @param {{videoWidth:number, videoHeight:number}} source 视频源（video 元素或具备宽高的对象）
	* @param {number} dx 目标左上 x
	* @param {number} dy 目标左上 y
	* @param {number} dw 目标宽
	* @param {number} dh 目标高
	* @returns {boolean} 是否真的画了（源还没有尺寸时返回 false）
	*/
	function drawCover(context, source, dx, dy, dw, dh) {
		const sw = Number(source === null || source === void 0 ? void 0 : source.videoWidth) || 0;
		const sh = Number(source === null || source === void 0 ? void 0 : source.videoHeight) || 0;
		if (!sw || !sh) return false;
		const scale = Math.max(dw / sw, dh / sh);
		const cropW = dw / scale;
		const cropH = dh / scale;
		context.drawImage(source, (sw - cropW) / 2, (sh - cropH) / 2, cropW, cropH, dx, dy, dw, dh);
		return true;
	}
	/**
	* 三分屏画布合成器：左侧坐席桌面，右上客户摄像头，右下坐席摄像头。
	*
	* 布局与基线完全一致（640×540 + 320×270 ×2），改尺寸会与已有存证的画面比例对不上。
	*/
	var TripleScreenCompositor = class {
		/**
		* @param {{canvas:object, width?:number, height?:number,
		*          interval?:number, setInterval?:Function, clearInterval?:Function,
		*          labels?:object, timestamp?:()=>string}} options
		*/
		constructor(options = {}) {
			var _options$interval, _options$setInterval, _options$clearInterva;
			this.canvas = options.canvas;
			this.width = options.width || 960;
			this.height = options.height || 540;
			this.interval = (_options$interval = options.interval) !== null && _options$interval !== void 0 ? _options$interval : 100;
			this.labels = options.labels || {
				desktop: "坐席桌面",
				remote: "客户摄像头",
				local: "坐席摄像头"
			};
			this.timestamp = options.timestamp || (() => (/* @__PURE__ */ new Date()).toLocaleString("zh-CN"));
			this._setInterval = (_options$setInterval = options.setInterval) !== null && _options$setInterval !== void 0 ? _options$setInterval : globalThis.setInterval.bind(globalThis);
			this._clearInterval = (_options$clearInterva = options.clearInterval) !== null && _options$clearInterva !== void 0 ? _options$clearInterva : globalThis.clearInterval.bind(globalThis);
			this._context = null;
			this._timer = null;
			/** 三路源：desktop / remote / local，都是具备 videoWidth 的对象或 video 元素。 */
			this.sources = {
				desktop: null,
				remote: null,
				local: null
			};
			if (this.canvas) {
				this.canvas.width = this.width;
				this.canvas.height = this.height;
			}
		}
		get context() {
			var _this$canvas;
			if (!this._context && ((_this$canvas = this.canvas) === null || _this$canvas === void 0 ? void 0 : _this$canvas.getContext)) this._context = this.canvas.getContext("2d");
			return this._context;
		}
		setSource(key, source) {
			if (key in this.sources) this.sources[key] = source || null;
			return this.sources;
		}
		/** 画一帧。抽出来是为了让单测不必依赖 setInterval 就能验证布局。 */
		drawFrame() {
			const context = this.context;
			if (!context) return false;
			const { width, height } = this;
			context.fillStyle = "#101c2e";
			context.fillRect(0, 0, width, height);
			const desktopW = Math.round(width * 2 / 3);
			const sideW = width - desktopW;
			const sideH = Math.round(height / 2);
			if (ready(this.sources.desktop)) drawCover(context, this.sources.desktop, 0, 0, desktopW, height);
			if (ready(this.sources.remote)) drawCover(context, this.sources.remote, desktopW, 0, sideW, sideH);
			if (ready(this.sources.local)) drawCover(context, this.sources.local, desktopW, sideH, sideW, sideH);
			context.fillStyle = "#101c2e";
			context.globalAlpha = .72;
			context.fillRect(0, 0, desktopW, 28);
			context.fillRect(desktopW, 0, sideW, 28);
			context.fillRect(desktopW, sideH, sideW, 28);
			context.globalAlpha = 1;
			context.fillStyle = "#fff";
			context.font = "14px Microsoft YaHei";
			context.fillText(this.labels.desktop, 12, 20);
			context.fillText(this.labels.remote, desktopW + 12, 20);
			context.fillText(this.labels.local, desktopW + 12, sideH + 20);
			context.font = "12px Microsoft YaHei";
			context.fillText(`人工见证 · ${this.timestamp()}`, 12, height - 15);
			return true;
		}
		/** 启动合成循环；已有循环时先停掉，避免两个 timer 叠加把 CPU 吃满。 */
		start() {
			this.stop();
			this.drawFrame();
			this._timer = this._setInterval(() => this.drawFrame(), this.interval);
			return this._timer;
		}
		stop() {
			if (this._timer != null) {
				this._clearInterval(this._timer);
				this._timer = null;
			}
			return true;
		}
		/**
		* 取合成流。帧率沿用基线的 10fps：三分屏是存证用途，
		* 更高的帧率只带来更大的落盘体积，不带来更多可辨识信息。
		*/
		captureStream(frameRate = 10) {
			var _this$canvas2;
			if (!((_this$canvas2 = this.canvas) === null || _this$canvas2 === void 0 ? void 0 : _this$canvas2.captureStream)) return null;
			return this.canvas.captureStream(frameRate);
		}
	};
	var ready = (source) => {
		var _source$readyState;
		return Boolean(source && Number((_source$readyState = source.readyState) !== null && _source$readyState !== void 0 ? _source$readyState : 4) >= 2 && Number(source.videoWidth) > 0);
	};
	/**
	* 抓帧：返回 **Blob**（契约 §2.9.3）。
	*
	* @param {HTMLVideoElement|object} source 具备 videoWidth/videoHeight 的画面源
	* @param {{canvas?:object, type?:string, quality?:number, toBlob?:Function}} options
	* @returns {Promise<Blob|null>} 未收到画面时返回 **null**，绝不伪造占位图
	*/
	async function capture(source, options = {}) {
		var _source$readyState2, _options$canvas, _canvas$getContext, _options$toBlob;
		const width = Number(source === null || source === void 0 ? void 0 : source.videoWidth) || 0;
		const height = Number(source === null || source === void 0 ? void 0 : source.videoHeight) || 0;
		if (!source || Number((_source$readyState2 = source.readyState) !== null && _source$readyState2 !== void 0 ? _source$readyState2 : 0) < 2 || !width || !height) return null;
		const canvas = (_options$canvas = options.canvas) !== null && _options$canvas !== void 0 ? _options$canvas : createCanvas();
		if (!canvas) return null;
		canvas.width = width;
		canvas.height = height;
		const context = (_canvas$getContext = canvas.getContext) === null || _canvas$getContext === void 0 ? void 0 : _canvas$getContext.call(canvas, "2d");
		if (!context) return null;
		context.drawImage(source, 0, 0, width, height);
		const type = options.type || "image/png";
		const quality = options.quality;
		const toBlob = (_options$toBlob = options.toBlob) !== null && _options$toBlob !== void 0 ? _options$toBlob : ((cb) => canvas.toBlob(cb, type, quality));
		return new Promise((resolve) => {
			let settled = false;
			const done = (blob) => {
				if (settled) return;
				settled = true;
				resolve(blob && blob.size > 0 ? blob : null);
			};
			try {
				const result = toBlob(done);
				if (result && typeof result.then === "function") result.then(done).catch(() => done(null));
			} catch (error) {
				done(null);
			}
		});
	}
	function createCanvas() {
		if (typeof document !== "undefined" && document.createElement) return document.createElement("canvas");
		return null;
	}
	//#endregion
	//#region src/media/local.js
	/**
	* 本地媒体：摄像头、桌面采集、模拟流兜底。
	*
	* ★ MediaStream 只在本模块与 transport 内部流转，**绝不外泄**给业务侧。
	*
	* 三条基线判据：
	*  1. 桌面采集（`getDisplayMedia`）必须在**用户手势内**申请（§3.4）——
	*     本模块不缓存"稍后再取"，调用点必须就是点击处理器。
	*  2. **本地流复用**：`end()` 后轨道可能仍存活，复用时必须重新派发 `local`，
	*     否则预览挂不回去，后续每通只剩远端单向。
	*  3. 相机获取失败要有**模拟流兜底**（移动端）。
	*/
	/** 坐席摄像头规格：沿用基线的 960×540。 */
	var AGENT_VIDEO_CONSTRAINTS = {
		width: 960,
		height: 540
	};
	/** 坐席桌面采集规格：10fps 足够存证，再高只是徒增体积。 */
	var DESKTOP_CONSTRAINTS = {
		video: { frameRate: 10 },
		audio: false
	};
	/** 浏览器媒体错误名 → 人话提示；未知错误名也有文案，但**原始错误绝不丢**（挂 cause）。 */
	var MEDIA_ERROR_HINTS = {
		NotAllowedError: "权限被拒绝，请在浏览器提示中允许摄像头/麦克风访问后重试",
		SecurityError: "当前页面上下文不允许访问媒体设备（需 HTTPS 或本机地址）",
		NotFoundError: "未找到可用的摄像头或麦克风设备",
		OverconstrainedError: "当前设备不满足采集规格要求，请更换设备或放宽参数",
		NotReadableError: "设备被其它应用占用或无法读取，请关闭占用后重试",
		AbortError: "媒体采集被中断，请重试",
		NotSupportedError: "当前浏览器不支持该媒体能力"
	};
	/**
	* 把浏览器媒体错误包装成人话错误：按 `error.name` 给针对性提示，
	* 同时保留原始错误（`cause`）——不再用一句通用文案盖掉真实原因。
	*/
	function mediaError(headline, cause, advice) {
		var _ref, _cause$message;
		const name = (cause === null || cause === void 0 ? void 0 : cause.name) || "Error";
		const hint = (_ref = advice !== null && advice !== void 0 ? advice : MEDIA_ERROR_HINTS[name]) !== null && _ref !== void 0 ? _ref : "请重试，若持续失败请联系管理员";
		const detail = (_cause$message = cause === null || cause === void 0 ? void 0 : cause.message) !== null && _cause$message !== void 0 ? _cause$message : String(cause);
		const error = /* @__PURE__ */ new Error(`${headline}：${hint}（${name}：${detail}）`);
		error.cause = cause;
		return error;
	}
	/**
	* 释放路径的失败：清理继续做，引用照清，最后把失败如实合并抛出（不静默、不中断其余清理）。
	* 条目既可是 `{label, error}`（带环节名），也可直接是 Error / 字符串。
	*/
	function releaseError(headline, failures) {
		var _failures$0$error, _failures$;
		if (!failures.length) return null;
		const parts = failures.map((item) => {
			var _item$error$message, _item$error, _item$message;
			return (item === null || item === void 0 ? void 0 : item.label) && (item === null || item === void 0 ? void 0 : item.error) ? `${item.label}：${(_item$error$message = (_item$error = item.error) === null || _item$error === void 0 ? void 0 : _item$error.message) !== null && _item$error$message !== void 0 ? _item$error$message : item.error}` : (_item$message = item === null || item === void 0 ? void 0 : item.message) !== null && _item$message !== void 0 ? _item$message : item;
		});
		const error = /* @__PURE__ */ new Error(`${headline}：${parts.join("；")}`);
		error.cause = (_failures$0$error = (_failures$ = failures[0]) === null || _failures$ === void 0 ? void 0 : _failures$.error) !== null && _failures$0$error !== void 0 ? _failures$0$error : failures[0];
		return error;
	}
	var LocalMedia = class {
		/**
		* @param {{mediaDevices?:object, document?:object, AudioContext?:Function}} options
		*/
		constructor(options = {}) {
			var _ref2, _options$mediaDevices, _globalThis$navigator, _ref3, _options$document, _ref4, _ref5, _options$AudioContext;
			this.mediaDevices = (_ref2 = (_options$mediaDevices = options.mediaDevices) !== null && _options$mediaDevices !== void 0 ? _options$mediaDevices : (_globalThis$navigator = globalThis.navigator) === null || _globalThis$navigator === void 0 ? void 0 : _globalThis$navigator.mediaDevices) !== null && _ref2 !== void 0 ? _ref2 : null;
			this.document = (_ref3 = (_options$document = options.document) !== null && _options$document !== void 0 ? _options$document : globalThis.document) !== null && _ref3 !== void 0 ? _ref3 : null;
			this.AudioContext = (_ref4 = (_ref5 = (_options$AudioContext = options.AudioContext) !== null && _options$AudioContext !== void 0 ? _options$AudioContext : globalThis.AudioContext) !== null && _ref5 !== void 0 ? _ref5 : globalThis.webkitAudioContext) !== null && _ref4 !== void 0 ? _ref4 : null;
			this.local = null;
			this.desktop = null;
			this.desktopVideo = null;
			this.audioContext = null;
			/** 是否用了模拟流：用于如实提示"当前画面为占位"，不冒充真实采集。 */
			this.usingMockStream = false;
			/** 回退模拟流时记录的真实取流失败原因，供 controller 经 error 事件如实上报。 */
			this.mockFallbackReason = null;
		}
		get hasLocal() {
			var _this$local$getVideoT, _this$local;
			return Boolean(this.local && ((_this$local$getVideoT = (_this$local = this.local).getVideoTracks) === null || _this$local$getVideoT === void 0 ? void 0 : _this$local$getVideoT.call(_this$local).some((track) => track.readyState === "live")));
		}
		get hasDesktop() {
			var _this$desktop$getVide, _this$desktop;
			return Boolean(this.desktop && ((_this$desktop$getVide = (_this$desktop = this.desktop).getVideoTracks) === null || _this$desktop$getVide === void 0 ? void 0 : _this$desktop$getVide.call(_this$desktop).some((track) => track.readyState === "live")));
		}
		/**
		* 获取本地摄像头流。
		* 已存在可用流时直接复用——重建会把已有连接里的轨道一并换掉。
		*/
		async getUserMedia(constraints) {
			var _this$mediaDevices;
			if (this.hasLocal) return this.local;
			if (!((_this$mediaDevices = this.mediaDevices) === null || _this$mediaDevices === void 0 ? void 0 : _this$mediaDevices.getUserMedia)) throw new Error("请使用 HTTPS 或本机地址访问以开启摄像头");
			try {
				this.local = await this.mediaDevices.getUserMedia(constraints || {
					video: AGENT_VIDEO_CONSTRAINTS,
					audio: true
				});
			} catch (error) {
				throw mediaError("无法开启摄像头或麦克风", error);
			}
			this.usingMockStream = false;
			this.mockFallbackReason = null;
			return this.local;
		}
		/**
		* 移动端取流，宿主显式配置了 `mockStreamFactory` 时降级到模拟流。
		*
		* 降级顺序与基线一致：facingMode:'user' → 只要 video → 模拟流。
		* ★ 模拟流是**宿主显式选择**的退路，不是静默伪装：`usingMockStream` 置真，
		*   真实失败原因记进 `mockFallbackReason`，由 controller 经 error 事件如实上报。
		*/
		async ensureStream(mockFactory) {
			var _lastError$name2, _lastError$message2;
			if (this.hasLocal) return this.local;
			const attempts = [
				{
					video: { facingMode: "user" },
					audio: true
				},
				{
					video: true,
					audio: true
				},
				{ video: true }
			];
			let lastError = null;
			for (const constraints of attempts) try {
				this.local = await this.mediaDevices.getUserMedia(constraints);
				this.usingMockStream = false;
				this.mockFallbackReason = null;
				return this.local;
			} catch (error) {
				lastError = error;
			}
			if (typeof mockFactory === "function") {
				this.local = mockFactory();
				this.usingMockStream = Boolean(this.local);
				if (this.local) {
					var _lastError$name, _lastError$message;
					this.mockFallbackReason = lastError ? `${(_lastError$name = lastError.name) !== null && _lastError$name !== void 0 ? _lastError$name : "Error"}：${(_lastError$message = lastError.message) !== null && _lastError$message !== void 0 ? _lastError$message : lastError}` : "未记录具体原因";
					return this.local;
				}
			}
			const error = /* @__PURE__ */ new Error("无法开启摄像头，且当前环境不支持模拟流兜底" + (lastError ? `（${(_lastError$name2 = lastError.name) !== null && _lastError$name2 !== void 0 ? _lastError$name2 : "Error"}：${(_lastError$message2 = lastError.message) !== null && _lastError$message2 !== void 0 ? _lastError$message2 : lastError}）` : ""));
			error.cause = lastError;
			throw error;
		}
		/**
		* 桌面采集。**必须在用户手势的同步调用链里**调用，否则浏览器直接拒绝。
		* 重复调用且上一段仍存活时直接复用。
		*/
		async getDisplayMedia(options = {}) {
			var _this$mediaDevices2, _this$desktop$getVide2, _this$desktop2, _this$desktop$getVide3;
			if (this.hasDesktop) return this.desktop;
			if (!((_this$mediaDevices2 = this.mediaDevices) === null || _this$mediaDevices2 === void 0 ? void 0 : _this$mediaDevices2.getDisplayMedia)) throw new Error("当前浏览器不支持坐席桌面录制");
			try {
				this.desktop = await this.mediaDevices.getDisplayMedia(options.constraints || DESKTOP_CONSTRAINTS);
			} catch (error) {
				throw mediaError("人工见证需共享坐席桌面以完成三分屏录制", error);
			}
			this.desktopVideo = this.createVideoElement();
			if (this.desktopVideo) {
				this.desktopVideo.muted = true;
				this.desktopVideo.playsInline = true;
				this.desktopVideo.srcObject = this.desktop;
				try {
					var _this$desktopVideo$pl, _this$desktopVideo;
					await ((_this$desktopVideo$pl = (_this$desktopVideo = this.desktopVideo).play) === null || _this$desktopVideo$pl === void 0 ? void 0 : _this$desktopVideo$pl.call(_this$desktopVideo));
				} catch (error) {
					const wrapped = mediaError("坐席桌面画面启动失败，三分屏无法呈现桌面", error, "画面无法自动启动（常见于浏览器自动播放策略拦截），请在用户手势内重试");
					try {
						this.releaseDesktop();
					} catch (releaseFailure) {
						wrapped.message = `${wrapped.message}；桌面释放也失败：${releaseFailure.message}`;
					}
					throw wrapped;
				}
			}
			(_this$desktop$getVide2 = (_this$desktop2 = this.desktop).getVideoTracks) === null || _this$desktop$getVide2 === void 0 || (_this$desktop$getVide2 = _this$desktop$getVide2.call(_this$desktop2)[0]) === null || _this$desktop$getVide2 === void 0 || (_this$desktop$getVide3 = _this$desktop$getVide2.addEventListener) === null || _this$desktop$getVide3 === void 0 || _this$desktop$getVide3.call(_this$desktop$getVide2, "ended", () => {
				var _options$onDesktopEnd;
				(_options$onDesktopEnd = options.onDesktopEnded) === null || _options$onDesktopEnd === void 0 || _options$onDesktopEnd.call(options);
			});
			return this.desktop;
		}
		createVideoElement() {
			var _this$document;
			if (!((_this$document = this.document) === null || _this$document === void 0 ? void 0 : _this$document.createElement)) return null;
			const video = this.document.createElement("video");
			video.muted = true;
			video.playsInline = true;
			return video;
		}
		/**
		* 把两路流的音频混成一路，供合成流使用。
		* 源：`agent_evidence.js` 的 AudioContext + MediaStreamDestination。
		*/
		mixAudio(streams = []) {
			var _this$audioContext$cr, _this$audioContext, _this$mixedSources, _destination$stream;
			if (!this.AudioContext) return null;
			if (!this.audioContext) try {
				this.audioContext = new this.AudioContext();
			} catch (error) {
				return null;
			}
			const destination = (_this$audioContext$cr = (_this$audioContext = this.audioContext).createMediaStreamDestination) === null || _this$audioContext$cr === void 0 ? void 0 : _this$audioContext$cr.call(_this$audioContext);
			if (!destination) return null;
			(_this$mixedSources = this.mixedSources) === null || _this$mixedSources === void 0 || _this$mixedSources.forEach((node) => {
				var _node$disconnect;
				return (_node$disconnect = node.disconnect) === null || _node$disconnect === void 0 ? void 0 : _node$disconnect.call(node);
			});
			this.mixedSources = /* @__PURE__ */ new Map();
			this.mixDestination = destination;
			streams.filter(Boolean).forEach((stream) => this.addMixedStream(stream));
			return (_destination$stream = destination.stream) !== null && _destination$stream !== void 0 ? _destination$stream : null;
		}
		addMixedStream(stream) {
			var _stream$getAudioTrack, _this$mixedSources2;
			if (!this.mixDestination || !(stream === null || stream === void 0 || (_stream$getAudioTrack = stream.getAudioTracks) === null || _stream$getAudioTrack === void 0 ? void 0 : _stream$getAudioTrack.call(stream).length) || ((_this$mixedSources2 = this.mixedSources) === null || _this$mixedSources2 === void 0 ? void 0 : _this$mixedSources2.has(stream))) return;
			const source = this.audioContext.createMediaStreamSource(stream);
			source.connect(this.mixDestination);
			this.mixedSources.set(stream, source);
		}
		removeMixedStream(stream) {
			var _this$mixedSources3, _source$disconnect, _this$mixedSources4;
			const source = (_this$mixedSources3 = this.mixedSources) === null || _this$mixedSources3 === void 0 ? void 0 : _this$mixedSources3.get(stream);
			source === null || source === void 0 || (_source$disconnect = source.disconnect) === null || _source$disconnect === void 0 || _source$disconnect.call(source);
			(_this$mixedSources4 = this.mixedSources) === null || _this$mixedSources4 === void 0 || _this$mixedSources4.delete(stream);
		}
		/**
		* 切换本地轨道开关（静音 / 暂停画面）。
		* @returns {boolean|null} 切换后的状态；无轨道时返回 null（调用方据此提示"请先连接音视频"）
		*/
		toggle(kind) {
			var _this$local2, _this$local2$getAudio, _this$local3, _this$local3$getVideo;
			const tracks = kind === "audio" ? (_this$local2 = this.local) === null || _this$local2 === void 0 || (_this$local2$getAudio = _this$local2.getAudioTracks) === null || _this$local2$getAudio === void 0 ? void 0 : _this$local2$getAudio.call(_this$local2) : (_this$local3 = this.local) === null || _this$local3 === void 0 || (_this$local3$getVideo = _this$local3.getVideoTracks) === null || _this$local3$getVideo === void 0 ? void 0 : _this$local3$getVideo.call(_this$local3);
			if (!(tracks === null || tracks === void 0 ? void 0 : tracks.length)) return null;
			const enabled = !tracks[0].enabled;
			tracks.forEach((track) => {
				track.enabled = enabled;
			});
			return enabled;
		}
		/** 按接管模式开关视频轨：audio 模式下坐席视频不参与呈现，停轨省带宽。 */
		setVideoEnabled(enabled) {
			var _this$local4, _this$local4$getVideo;
			const tracks = ((_this$local4 = this.local) === null || _this$local4 === void 0 || (_this$local4$getVideo = _this$local4.getVideoTracks) === null || _this$local4$getVideo === void 0 ? void 0 : _this$local4$getVideo.call(_this$local4)) || [];
			tracks.forEach((track) => {
				track.enabled = Boolean(enabled);
			});
			return tracks.length > 0;
		}
		releaseDesktop() {
			var _this$desktop$getTrac, _this$desktop3;
			const failures = [];
			if (this.desktop) (_this$desktop$getTrac = (_this$desktop3 = this.desktop).getTracks) === null || _this$desktop$getTrac === void 0 || _this$desktop$getTrac.call(_this$desktop3).forEach((track, index) => {
				try {
					track.stop();
				} catch (error) {
					failures.push({
						label: `停止桌面轨道 #${index}`,
						error
					});
				}
			});
			if (this.desktopVideo) this.desktopVideo.srcObject = null;
			this.desktop = null;
			this.desktopVideo = null;
			const error = releaseError("释放坐席桌面", failures);
			if (error) throw error;
			return true;
		}
		/**
		* 释放本地流。
		* ★ 注意：stop() 之后轨道对象可能仍在（浏览器实现的差异），
		*   所以这里必须把引用一并清掉，逼迫下次走完整的 getUserMedia，
		*   避免复用到一个"看起来 live 实际没画面"的僵尸流。
		*/
		releaseLocal() {
			var _this$local$getTracks, _this$local5;
			const failures = [];
			if (this.local) (_this$local$getTracks = (_this$local5 = this.local).getTracks) === null || _this$local$getTracks === void 0 || _this$local$getTracks.call(_this$local5).forEach((track, index) => {
				try {
					track.stop();
				} catch (error) {
					failures.push({
						label: `停止本地轨道 #${index}`,
						error
					});
				}
			});
			this.local = null;
			this.usingMockStream = false;
			const error = releaseError("释放本地流", failures);
			if (error) throw error;
			return true;
		}
		async releaseAudio() {
			var _this$mixedSources5;
			const failures = [];
			if (this.audioContext) try {
				await this.audioContext.close();
			} catch (error) {
				failures.push({
					label: "关闭 AudioContext",
					error
				});
			}
			this.audioContext = null;
			this.mixDestination = null;
			(_this$mixedSources5 = this.mixedSources) === null || _this$mixedSources5 === void 0 || _this$mixedSources5.clear();
			const error = releaseError("释放音频上下文", failures);
			if (error) throw error;
			return true;
		}
		async release() {
			const failures = [];
			try {
				this.releaseDesktop();
			} catch (error) {
				failures.push(error);
			}
			try {
				this.releaseLocal();
			} catch (error) {
				failures.push(error);
			}
			try {
				await this.releaseAudio();
			} catch (error) {
				failures.push(error);
			}
			const error = releaseError("媒体释放未完全成功", failures);
			if (error) throw error;
			return true;
		}
	};
	//#endregion
	//#region src/media/surface.js
	/**
	* 画面承载面（Surface）—— 控件内部把 MediaStream 与 `<video>` DOM 收在这一层。
	*
	* ★ 隔离目的：内核（controller / recorder / capture）**完全不碰 srcObject**，
	*   只通过 `attach(key, stream)` / `source(key)` 与画面源打交道。
	*   于是：
	*     - 内核可以直接在 node 里单测（注入 NullSurface 即可）；
	*     - 业务侧更不可能拿到 MediaStream（连内核都没有引用，只有视图层有）；
	*     - 换渲染层（Vue / 未来别的框架）时命令 API 一行不改。
	*
	* 视图层（stage.js 的 VideoStage）实现真身；单测与无 DOM 环境用 NullSurface。
	*/
	var SURFACE_KEYS = Object.freeze([
		"remote",
		"secondary",
		"local",
		"desktop"
	]);
	/** 无 DOM 环境的空实现：attach 只记账，source 返回 null。 */
	var NullSurface = class {
		constructor() {
			/** @type {Map<string, object>} */
			this.attached = /* @__PURE__ */ new Map();
			this.canvas = null;
		}
		attach(key, stream) {
			if (!SURFACE_KEYS.includes(key)) throw new Error(`未知的画面槽位：${key}`);
			this.attached.set(key, stream || null);
			return stream || null;
		}
		detach(key) {
			this.attached.delete(key);
			return true;
		}
		source(key) {
			var _this$attached$get;
			return (_this$attached$get = this.attached.get(key)) !== null && _this$attached$get !== void 0 ? _this$attached$get : null;
		}
		/** 供三分屏合成用的画布。NullSurface 下为 null，录制会退化为"不合成"。 */
		createCanvas() {
			return this.canvas;
		}
		setCanvas(canvas) {
			this.canvas = canvas;
			return canvas;
		}
		/** 抓帧用的可绘制源：View 返回真实 video 元素；这里返回 attach 进来的对象。 */
		drawable(key) {
			return this.source(key);
		}
		clear() {
			this.attached.clear();
			return true;
		}
	};
	/**
	* DOM 版承载面：由视图层构造，把流真正挂到 `<video>` 元素上。
	* ★ 这是整个控件里**唯一**出现 `srcObject` 的地方（另有 local.js 的桌面视频元素）。
	*   业务侧任何文件都不允许出现该字样。
	*/
	var DomSurface = class extends NullSurface {
		/**
		* @param {{elements?:Record<string, HTMLVideoElement>, document?:object,
		*          createCanvas?:()=>HTMLCanvasElement}} options
		*/
		constructor(options = {}) {
			var _ref, _options$document, _options$createCanvas;
			super();
			this.document = (_ref = (_options$document = options.document) !== null && _options$document !== void 0 ? _options$document : globalThis.document) !== null && _ref !== void 0 ? _ref : null;
			this.elements = { ...options.elements || {} };
			this._createCanvas = (_options$createCanvas = options.createCanvas) !== null && _options$createCanvas !== void 0 ? _options$createCanvas : (() => {
				var _this$document$create, _this$document, _this$document$create2;
				return (_this$document$create = (_this$document = this.document) === null || _this$document === void 0 || (_this$document$create2 = _this$document.createElement) === null || _this$document$create2 === void 0 ? void 0 : _this$document$create2.call(_this$document, "canvas")) !== null && _this$document$create !== void 0 ? _this$document$create : null;
			});
		}
		element(key) {
			var _this$elements$key;
			return (_this$elements$key = this.elements[key]) !== null && _this$elements$key !== void 0 ? _this$elements$key : null;
		}
		bind(key, element) {
			this.elements[key] = element;
			const pending = this.attached.get(key);
			if (pending && element && element.srcObject !== pending) {
				var _element$play;
				element.srcObject = pending;
				(_element$play = element.play) === null || _element$play === void 0 || _element$play.call(element).catch(() => {});
			}
			return element;
		}
		attach(key, stream) {
			super.attach(key, stream);
			const element = this.element(key);
			if (element) {
				var _element$play2;
				if (element.srcObject !== stream) element.srcObject = stream;
				if (stream) (_element$play2 = element.play) === null || _element$play2 === void 0 || _element$play2.call(element).catch(() => {});
			}
			return stream || null;
		}
		detach(key) {
			const element = this.element(key);
			if (element) element.srcObject = null;
			return super.detach(key);
		}
		createCanvas() {
			if (!this.canvas) this.canvas = this._createCanvas();
			return this.canvas;
		}
		/** 抓帧必须拿真实元素：只有它有 videoWidth / readyState。 */
		drawable(key) {
			var _this$element;
			return (_this$element = this.element(key)) !== null && _this$element !== void 0 ? _this$element : this.source(key);
		}
		clear() {
			SURFACE_KEYS.forEach((key) => this.detach(key));
			return super.clear();
		}
	};
	//#endregion
	//#region src/controller.js
	/**
	* 视频控件命令 API —— ★ 业务侧唯一入口（契约 §2.9.3）。
	*
	* ```
	* video.open({ mode, layout });          // 挂载控件
	* video.close();                         // 卸载并释放
	* video.connect({ peerId, processId });  // 建链（坐席端接听后调用）
	* video.hangup();
	* video.takeover('av' | 'audio');        // 接管模式，服务端下发为准
	* video.toggle('audio' | 'video');       // 本地静音 / 暂停画面
	* video.capture('remote' | 'local');     // ★ 抓帧，返回 Blob
	* video.record.start({ processId, bizNo, nodeName });
	* video.record.seal();                   // ★ 返回封存回执，含 fileSize
	* video.on('state', cb);
	* video.getState();
	* ```
	*
	* ★ 内核纯 JS：**不 import vue、不碰 DOM、不持有 MediaStream**（流全部经 surface 注入/读取）。
	*   因此本文件可以直接在 node 里跑状态机单测。
	*
	* ★ 业务侧红线：
	*   1. 只 import 本模块，**禁止** import `@/core/media/**`、禁止出现 `srcObject` / `MediaStream`。
	*   2. 唯一强依赖是 `capture()` 与 `record.seal()` 的返回值。
	*   3. `seal()` 回执 `fileSize === 0` 时**不得宣告存证成功**（§3.4），必须如实提示。
	*   4. 除这两点外不得假设视频存在——无视频时业务必须仍能办理到底。
	*/
	var MODE = Object.freeze({
		OVERLAY: "overlay",
		DOCK: "dock"
	});
	var TAKEOVER = Object.freeze({
		AV: "av",
		AUDIO: "audio",
		RELEASED: "released"
	});
	var CONNECTION = Object.freeze({
		IDLE: "idle",
		CONNECTING: "connecting",
		CONNECTED: "connected",
		ENDED: "ended",
		ERROR: "error"
	});
	/**
	* 收集释放 / 清理路径上的失败：清理必须做完整（§8.4-5 停全部 track、§3.4 封存必达），
	* 但失败**不许静默吞掉**——先记账，由发起清理的命令统一上报（state.error + error 事件 + 抛错）。
	* 同步抛错与 Promise 拒绝都会被收进 failures，调用方可统一 await。
	*/
	function collectFailures(failures, label, run) {
		try {
			const result = run();
			return Promise.resolve(result).then(() => void 0, (error) => {
				failures.push({
					label,
					error
				});
			});
		} catch (error) {
			failures.push({
				label,
				error
			});
			return Promise.resolve(void 0);
		}
	}
	/** 把收集到的失败拼成人话（环节：原因；环节：原因）。 */
	function summarizeFailures(failures) {
		return failures.map((item) => {
			var _item$error$message, _item$error;
			return `${item.label}：${(_item$error$message = (_item$error = item.error) === null || _item$error === void 0 ? void 0 : _item$error.message) !== null && _item$error$message !== void 0 ? _item$error$message : item.error}`;
		}).join("；");
	}
	/**
	* 创建控件实例。默认导出的是共享单例（业务侧通常只需要一个），
	* 但**工厂可复用**——单测与将来"一屏多路"都要能各建各的。
	*
	* @param {{surface?:object, transport?:object, media?:object, queue?:object,
	*          upload?:Function, transportName?:string, transportOptions?:object,
	*          storage?:object, onState?:Function}} options
	*/
	function createVideoController(options = {}) {
		var _options$surface, _options$media, _options$transport, _options$upload;
		let surface = (_options$surface = options.surface) !== null && _options$surface !== void 0 ? _options$surface : new NullSurface();
		const media = (_options$media = options.media) !== null && _options$media !== void 0 ? _options$media : new LocalMedia(options.mediaOptions || {});
		const listeners = /* @__PURE__ */ new Map();
		const timers = /* @__PURE__ */ new Set();
		let transport = (_options$transport = options.transport) !== null && _options$transport !== void 0 ? _options$transport : null;
		const remoteStreams = /* @__PURE__ */ new Map();
		let participants = [];
		let localPeerId = null;
		let primaryPeerId = null;
		let recorder = null;
		let compositor = null;
		let compositeStream = null;
		let unsubscribes = [];
		/** 最近一次远端流：仅用于换承载面时迁移，**不进 state**（state 快照不得携带 MediaStream）。 */
		let remoteStream = null;
		const state = {
			mounted: false,
			mode: MODE.OVERLAY,
			layout: { ...DEFAULT_LAYOUT },
			pixel: null,
			draggable: false,
			connection: CONNECTION.IDLE,
			call: false,
			recording: false,
			takeover: TAKEOVER.AV,
			audioEnabled: true,
			videoEnabled: true,
			hasRemote: false,
			hasLocal: false,
			hasDesktop: false,
			usingMockStream: false,
			recordingId: null,
			peerId: null,
			processId: null,
			bizNo: null,
			error: null
		};
		function on(event, handler) {
			if (typeof handler !== "function") return () => {};
			if (!listeners.has(event)) listeners.set(event, /* @__PURE__ */ new Set());
			listeners.get(event).add(handler);
			return () => off(event, handler);
		}
		function off(event, handler) {
			var _listeners$get;
			(_listeners$get = listeners.get(event)) === null || _listeners$get === void 0 || _listeners$get.delete(handler);
		}
		function emit(event, payload) {
			const handlers = listeners.get(event);
			if (!handlers) return;
			for (const handler of [...handlers]) try {
				handler(payload);
			} catch (error) {
				console.error(`[video] ${event} 订阅者异常`, error);
			}
		}
		function set(patch = {}) {
			let changed = false;
			Object.entries(patch).forEach(([key, value]) => {
				if (state[key] !== value) {
					state[key] = value;
					changed = true;
				}
			});
			if (changed) emit("state", { ...state });
			return changed;
		}
		const getState = () => ({ ...state });
		function ensureTransport(localPeerId) {
			var _ref, _options$transportOpt;
			if (transport) return transport;
			transport = createTransport(options.transportName, {
				role: "agent",
				peerId: (_ref = localPeerId !== null && localPeerId !== void 0 ? localPeerId : (_options$transportOpt = options.transportOptions) === null || _options$transportOpt === void 0 ? void 0 : _options$transportOpt.peerId) !== null && _ref !== void 0 ? _ref : null,
				...options.transportOptions || {}
			});
			wire(transport);
			return transport;
		}
		/**
		* 换传输实现：业务侧只传**逻辑名**（'p2p' / 'vendor'），
		* 不出现厂商字样；真正登记在 `transport/registry.js`。
		*/
		function useTransport(name, transportOptions = {}) {
			const previous = transport;
			if (previous) {
				const failures = [];
				unsubscribes.forEach((fn) => collectFailures(failures, "取消旧传输事件订阅", fn));
				unsubscribes = [];
				collectFailures(failures, "释放旧传输", () => {
					var _previous$destroy;
					return (_previous$destroy = previous.destroy) === null || _previous$destroy === void 0 ? void 0 : _previous$destroy.call(previous);
				}).then(() => {
					if (failures.length) emit("error", {
						error: /* @__PURE__ */ new Error(`切换传输实现时旧传输释放不完整：${summarizeFailures(failures)}`),
						fatal: false,
						phase: "use-transport"
					});
				});
			}
			transport = createTransport(name, {
				role: "agent",
				...transportOptions
			});
			wire(transport);
			return transport;
		}
		function wire(instance) {
			unsubscribes = [
				instance.on(TransportEvent.STATE, ({ state: next }) => {
					set({
						connection: next === TransportState.CONNECTED ? CONNECTION.CONNECTED : next === TransportState.CONNECTING || next === TransportState.RECONNECTING ? CONNECTION.CONNECTING : next === TransportState.ERROR ? CONNECTION.ERROR : next === TransportState.CLOSED || next === TransportState.DISCONNECTED ? CONNECTION.ENDED : CONNECTION.IDLE,
						error: next === TransportState.ERROR ? state.error : null
					});
				}),
				instance.on(TransportEvent.LOCAL, ({ stream }) => {
					surface.attach("local", stream);
					set({ hasLocal: Boolean(stream) });
				}),
				instance.on(TransportEvent.REMOTE, ({ stream, peerId }) => {
					var _media$addMixedStream;
					remoteStreams.set(peerId || state.peerId || "remote", stream);
					(_media$addMixedStream = media.addMixedStream) === null || _media$addMixedStream === void 0 || _media$addMixedStream.call(media, stream);
					renderRemotes();
				}),
				instance.on(TransportEvent.PEER_LEFT, ({ peerId } = {}) => {
					if (peerId) {
						var _media$removeMixedStr;
						(_media$removeMixedStr = media.removeMixedStream) === null || _media$removeMixedStr === void 0 || _media$removeMixedStr.call(media, remoteStreams.get(peerId));
						remoteStreams.delete(peerId);
					} else remoteStreams.clear();
					renderRemotes();
				}),
				instance.on(TransportEvent.ERROR, ({ error, fatal }) => {
					var _error$message;
					const detail = error instanceof Error ? error : new Error((_error$message = error === null || error === void 0 ? void 0 : error.message) !== null && _error$message !== void 0 ? _error$message : String(error));
					if (fatal) set({
						error: detail.message,
						connection: CONNECTION.ERROR
					});
					emit("error", {
						error: detail,
						fatal: Boolean(fatal),
						phase: "transport"
					});
				})
			];
		}
		function orderedRemoteEntries() {
			var _participants$find;
			const preferredRole = ((_participants$find = participants.find((member) => member.peerId === localPeerId)) === null || _participants$find === void 0 ? void 0 : _participants$find.role) === "CUSTOMER" ? "AGENT" : "CUSTOMER";
			const entries = [...remoteStreams.entries()];
			entries.sort(([left], [right]) => {
				var _participants$find2, _participants$find3;
				if (left === primaryPeerId) return -1;
				if (right === primaryPeerId) return 1;
				return Number(((_participants$find2 = participants.find((p) => p.peerId === right)) === null || _participants$find2 === void 0 ? void 0 : _participants$find2.role) === preferredRole) - Number(((_participants$find3 = participants.find((p) => p.peerId === left)) === null || _participants$find3 === void 0 ? void 0 : _participants$find3.role) === preferredRole);
			});
			return entries;
		}
		function renderRemotes() {
			var _entries$, _entries$2, _entries$3;
			const entries = orderedRemoteEntries();
			if (primaryPeerId && !remoteStreams.has(primaryPeerId)) primaryPeerId = null;
			remoteStream = ((_entries$ = entries[0]) === null || _entries$ === void 0 ? void 0 : _entries$[1]) || null;
			surface.attach("remote", remoteStream);
			surface.attach("secondary", ((_entries$2 = entries[1]) === null || _entries$2 === void 0 ? void 0 : _entries$2[1]) || null);
			set({
				hasRemote: Boolean(remoteStream),
				hasSecondaryRemote: Boolean((_entries$3 = entries[1]) === null || _entries$3 === void 0 ? void 0 : _entries$3[1]),
				call: entries.length > 0,
				participants: [...participants]
			});
		}
		/** 将当前小窗切换为主画面。三人房间中各端均可在两路远端画面间切换。 */
		function swapRemote() {
			const entries = orderedRemoteEntries();
			if (entries.length < 2) return false;
			primaryPeerId = entries[1][0];
			renderRemotes();
			return true;
		}
		async function updateParticipants(next = []) {
			var _instance$allowFrom;
			if (!Array.isArray(next) || next.some((member) => !(member === null || member === void 0 ? void 0 : member.peerId) || !member.role)) throw new Error("房间成员信息不完整");
			const instance = ensureTransport(localPeerId);
			const ids = new Set(next.map((member) => member.peerId));
			for (const old of participants) if (!ids.has(old.peerId)) {
				var _instance$denyFrom, _instance$hangup;
				(_instance$denyFrom = instance.denyFrom) === null || _instance$denyFrom === void 0 || _instance$denyFrom.call(instance, old.peerId);
				await ((_instance$hangup = instance.hangup) === null || _instance$hangup === void 0 ? void 0 : _instance$hangup.call(instance, old.peerId));
				remoteStreams.delete(old.peerId);
			}
			participants = next.map((member) => ({ ...member }));
			(_instance$allowFrom = instance.allowFrom) === null || _instance$allowFrom === void 0 || _instance$allowFrom.call(instance, participants.filter((member) => member.peerId !== localPeerId).map((member) => member.peerId));
			renderRemotes();
			return getState();
		}
		if (transport) wire(transport);
		/** 挂载控件。只管状态与布局，不申请任何媒体权限（同意告知书前不得申请）。 */
		async function open(config = {}) {
			var _config$pixel;
			if (config.mode != null && config.mode !== MODE.OVERLAY && config.mode !== MODE.DOCK) throw new Error(`不支持的窗口模式：${config.mode}（仅接受 overlay / dock）`);
			if (config.layout != null && (typeof config.layout !== "object" || Array.isArray(config.layout))) throw new Error(`layout 必须是归一化布局对象，收到：${Array.isArray(config.layout) ? "array" : typeof config.layout}`);
			state.mode = config.mode === MODE.DOCK ? MODE.DOCK : MODE.OVERLAY;
			state.draggable = Boolean(config.draggable);
			state.layout = state.mode === MODE.DOCK ? {
				...DEFAULT_DOCK_LAYOUT,
				...config.layout || {}
			} : {
				...DEFAULT_LAYOUT,
				...config.layout || {}
			};
			state.pixel = (_config$pixel = config.pixel) !== null && _config$pixel !== void 0 ? _config$pixel : null;
			set({
				mounted: true,
				mode: state.mode,
				layout: state.layout,
				pixel: state.pixel,
				draggable: state.draggable
			});
			return { ...state };
		}
		/** 卸载并释放：业务标签关闭、挂断或签出时调用。 */
		async function close() {
			const failures = await stopAll();
			if (failures.length) {
				const error = /* @__PURE__ */ new Error(`控件已关闭，但清理未完全成功：${summarizeFailures(failures)}`);
				set({
					mounted: false,
					hasRemote: false,
					hasLocal: false,
					hasDesktop: false,
					connection: CONNECTION.IDLE,
					call: false,
					recording: false,
					error: error.message
				});
				emit("error", {
					error,
					fatal: true,
					phase: "close"
				});
				throw error;
			}
			set({
				mounted: false,
				hasRemote: false,
				hasLocal: false,
				hasDesktop: false,
				connection: CONNECTION.IDLE,
				call: false,
				recording: false,
				error: null
			});
			return { ...state };
		}
		/**
		* 释放全部资源。清理失败**不中断**其余清理步骤（红线要求全部停掉），
		* 但每一条都被收集返回，由 close()/hangup() 统一置错 + 广播 + 抛出。
		*
		* @returns {Promise<{label:string, error:Error}[]>}
		*/
		async function stopAll() {
			const failures = [];
			timers.forEach((timer) => clearInterval(timer));
			timers.clear();
			if (recorder === null || recorder === void 0 ? void 0 : recorder.active) await collectFailures(failures, "放弃未封存的录制", () => recorder.abort());
			await collectFailures(failures, "停止画面合成", () => stopComposite());
			unsubscribes.forEach((fn) => collectFailures(failures, "取消传输事件订阅", fn));
			unsubscribes = [];
			if (transport) {
				await collectFailures(failures, "释放传输", () => transport.destroy());
				transport = null;
			}
			remoteStream = null;
			primaryPeerId = null;
			remoteStreams.clear();
			participants = [];
			await collectFailures(failures, "释放媒体轨道", () => media.release());
			await collectFailures(failures, "清空承载面", () => surface.clear());
			return failures;
		}
		/**
		* 换画面承载面：视图层挂载/重建时把已有的流迁移到新 surface。
		*
		* 内核仍然不把 MediaStream 放进 `state`——迁移只用 media 上的本地/桌面流
		* 与闭包内的 `remoteStream`，业务侧依然只能经 capture()/seal() 拿到结果。
		*
		* @param {object} next 实现了 attach/detach 的承载面（通常是视图层的 DomSurface）
		*/
		function useSurface(next) {
			if (!next || next === surface) return surface;
			surface = next;
			if (media.local) surface.attach("local", media.local);
			if (media.desktop) surface.attach("desktop", media.desktop);
			renderRemotes();
			return surface;
		}
		/**
		* 建链。**坐席端必须在接听的用户手势内调用**——桌面采集（`getDisplayMedia`）
		* 只有在手势的同步调用链里才被允许（§3.4）。
		*
		* @param {{peerId?:string, localPeerId?:string, processId?:string, localStream?:MediaStream,
		*          desktop?:boolean, mockStream?:boolean}} config
		*/
		async function connect(config = {}) {
			var _config$localPeerId, _config$processId, _config$peerId, _config$localStream;
			localPeerId = (_config$localPeerId = config.localPeerId) !== null && _config$localPeerId !== void 0 ? _config$localPeerId : localPeerId;
			if (!state.mounted) throw new Error("控件尚未初始化：请先调用 video.open({ mode, layout }) 再 connect()（open 只挂载状态，不申请媒体权限）");
			state.processId = (_config$processId = config.processId) !== null && _config$processId !== void 0 ? _config$processId : state.processId;
			state.peerId = (_config$peerId = config.peerId) !== null && _config$peerId !== void 0 ? _config$peerId : state.peerId;
			let stream = (_config$localStream = config.localStream) !== null && _config$localStream !== void 0 ? _config$localStream : null;
			if (!stream) try {
				stream = config.mockStream ? await media.ensureStream(options.mockStreamFactory) : await media.getUserMedia(config.constraints);
			} catch (error) {
				set({
					connection: CONNECTION.ERROR,
					error: error.message
				});
				throw error;
			}
			else media.local = stream;
			state.usingMockStream = Boolean(media.usingMockStream);
			if (state.usingMockStream) emit("error", {
				error: /* @__PURE__ */ new Error(`真实取流失败，已按宿主显式配置回退到模拟流（界面徽标显示"演示画面"）：${media.mockFallbackReason || "未记录具体原因"}`),
				fatal: false,
				phase: "mock-stream"
			});
			if (config.desktop !== false) try {
				await media.getDisplayMedia({
					constraints: config.desktopConstraints,
					onDesktopEnded: () => {
						if (recorder === null || recorder === void 0 ? void 0 : recorder.active) record.seal().catch((error) => {
							var _error$message2;
							set({ error: `桌面共享中断，录制封存失败：${(_error$message2 = error === null || error === void 0 ? void 0 : error.message) !== null && _error$message2 !== void 0 ? _error$message2 : error}` });
							emit("error", {
								error,
								fatal: false,
								phase: "desktop-ended-seal"
							});
						});
					}
				});
				surface.attach("desktop", media.desktop);
			} catch (error) {
				set({ error: error.message });
				emit("error", {
					error,
					fatal: false,
					phase: "desktop-capture"
				});
			}
			surface.attach("local", stream);
			set({
				hasLocal: Boolean(stream),
				hasDesktop: media.hasDesktop,
				usingMockStream: state.usingMockStream
			});
			const instance = ensureTransport(config.localPeerId);
			if (Array.isArray(config.participants)) await updateParticipants(config.participants);
			if (state.peerId && typeof instance.allowFrom === "function") instance.allowFrom(state.peerId);
			await instance.connect({
				peerId: state.peerId,
				processId: state.processId
			});
			await instance.publish(stream);
			if (config.dialAll === true) {
				for (const member of participants) if (member.peerId && member.peerId !== localPeerId) await instance.subscribe(member.peerId, { metadata: { processId: state.processId } });
			} else if (state.peerId && !config.incomingOnly) await instance.subscribe(state.peerId, { metadata: { processId: state.processId } });
			else set({ call: false });
			return { ...state };
		}
		/**
		* 仅注册本端信令标识，不挂载窗口、不申请摄像头/麦克风、也不发起外呼。
		* 渠道端在收到服务端 VIDEO_ROOM_READY 前调用它，确保坐席接听后可以找到客户。
		*/
		async function prepare(config = {}) {
			var _config$localPeerId2, _config$processId2;
			localPeerId = (_config$localPeerId2 = config.localPeerId) !== null && _config$localPeerId2 !== void 0 ? _config$localPeerId2 : localPeerId;
			if (!config.localPeerId) throw new Error("预注册视频端点需要 localPeerId");
			if ((transport === null || transport === void 0 ? void 0 : transport.peerId) && transport.peerId !== config.localPeerId) {
				await transport.destroy();
				unsubscribes.forEach((off) => off());
				unsubscribes = [];
				transport = null;
			}
			state.processId = (_config$processId2 = config.processId) !== null && _config$processId2 !== void 0 ? _config$processId2 : state.processId;
			const instance = ensureTransport(config.localPeerId);
			if (config.peerId && typeof instance.allowFrom === "function") instance.allowFrom(config.peerId);
			await instance.connect({
				localPeerId: config.localPeerId,
				processId: state.processId
			});
			return { ...state };
		}
		async function hangup() {
			const failures = [];
			await collectFailures(failures, "停止画面合成", () => stopComposite());
			if (recorder === null || recorder === void 0 ? void 0 : recorder.active) await collectFailures(failures, "封存进行中的录制", () => record.seal());
			await collectFailures(failures, "挂断传输", () => transport === null || transport === void 0 ? void 0 : transport.hangup());
			remoteStream = null;
			surface.detach("remote");
			await collectFailures(failures, "释放媒体轨道", () => media.release());
			surface.detach("local");
			surface.detach("desktop");
			set({
				call: false,
				hasRemote: false,
				hasLocal: false,
				hasDesktop: false,
				usingMockStream: false,
				connection: CONNECTION.ENDED
			});
			if (failures.length) {
				const error = /* @__PURE__ */ new Error(`挂断已执行，但以下环节失败：${summarizeFailures(failures)}`);
				set({ error: error.message });
				emit("error", {
					error,
					fatal: true,
					phase: "hangup"
				});
				throw error;
			}
			return { ...state };
		}
		/**
		* 切换接管模式。
		* ★ 控件**不下业务判断**：只按服务端下发的 `takeover_mode` 渲染/执行。
		*   audio 模式下坐席视频不参与呈现，停掉视频轨省带宽；切回 av 再开回来。
		*/
		async function takeover(mode) {
			if (!Object.values(TAKEOVER).includes(mode)) throw new Error(`不支持的接管模式：${mode}`);
			if (state.call) media.setVideoEnabled(mode === TAKEOVER.AV);
			set({
				takeover: mode,
				videoEnabled: mode === TAKEOVER.AV ? state.videoEnabled : false
			});
			return mode;
		}
		/** 本地静音 / 暂停画面。无轨道时如实报错（先 connect 才有轨道），不再原样返回当前值。 */
		async function toggle(kind) {
			if (kind !== "audio" && kind !== "video") throw new Error(`不支持的开关：${kind}`);
			const next = media.toggle(kind);
			if (next === null || next === void 0) throw new Error(`当前没有本地${kind === "audio" ? "音频" : "视频"}轨道，无法切换：请先 connect() 取得本地流后再调用`);
			set(kind === "audio" ? { audioEnabled: next } : { videoEnabled: next });
			return next;
		}
		/**
		* 抓帧。**返回 Blob**（人工授权现场照片依赖）。
		* 未收到画面时返回 **null**——绝不伪造占位图冒充成功。
		*/
		async function capture$1(source = "remote") {
			if (source !== "remote" && source !== "local") throw new Error(`不支持的抓帧源：${source}`);
			return capture(surface.drawable(source), options.captureOptions || {});
		}
		let recordingUpload = (_options$upload = options.upload) !== null && _options$upload !== void 0 ? _options$upload : null;
		function ensureRecorder(uploadOverride) {
			var _options$queue, _recordingUpload;
			if (typeof uploadOverride === "function" && uploadOverride !== recordingUpload) {
				if (recorder === null || recorder === void 0 ? void 0 : recorder.active) throw new Error("录像进行中，不能切换上传通道");
				recordingUpload = uploadOverride;
				recorder = null;
			}
			if (recorder) return recorder;
			recorder = new SegmentRecorder({
				queue: (_options$queue = options.queue) !== null && _options$queue !== void 0 ? _options$queue : new ChunkQueue({
					upload: (_recordingUpload = recordingUpload) !== null && _recordingUpload !== void 0 ? _recordingUpload : defaultUpload,
					indexedDB: options.indexedDB,
					Blob: options.Blob
				}),
				MediaRecorder: options.MediaRecorder,
				Blob: options.Blob,
				timeslice: options.timeslice
			});
			recorder.onError = (error) => set({ error: error.message });
			return recorder;
		}
		/** 独立控件不知道业务服务地址，必须由宿主注入录像分片上传能力。 */
		async function defaultUpload() {
			throw new Error("录像上传通道未配置");
		}
		/**
		* 三分屏合成：桌面 + 远端 + 本地。
		* ★ 合成是**存证要求**（已采集桌面时这一段必须是三分屏）：画布/captureStream/混音
		*   任何一路不可用都如实报错，**不再返回 null 让上层悄悄改录原始流**。
		*   确实只想录原始流时，由调用方显式传 `{ composite: false }`。
		*/
		function startComposite() {
			stopComposite();
			const canvas = surface.createCanvas();
			if (!canvas) throw new Error("三分屏合成不可用：承载面未提供画布（surface.createCanvas() 返回空），无法录制坐席桌面存证。请修复承载面，或显式以 { composite: false } 录制原始流");
			try {
				var _media$desktopVideo, _options$compositeFra;
				compositor = new TripleScreenCompositor({
					canvas,
					setInterval: options.setInterval,
					clearInterval: options.clearInterval,
					timestamp: options.timestamp
				});
				compositor.setSource("desktop", (_media$desktopVideo = media.desktopVideo) !== null && _media$desktopVideo !== void 0 ? _media$desktopVideo : surface.source("desktop"));
				compositor.setSource("remote", surface.drawable("remote"));
				compositor.setSource("local", surface.drawable("local"));
				compositor.start();
				compositeStream = compositor.captureStream((_options$compositeFra = options.compositeFrameRate) !== null && _options$compositeFra !== void 0 ? _options$compositeFra : 10);
				if (!compositeStream) throw new Error("三分屏合成不可用：画布不支持 captureStream()，无法录制坐席桌面存证。请修复承载面，或显式以 { composite: false } 录制原始流");
				if (state.takeover !== TAKEOVER.AUDIO) {
					var _mixed$getAudioTracks, _mixed$getAudioTracks2;
					const sources = [media.local, ...remoteStreams.values()].filter((stream) => {
						var _stream$getAudioTrack;
						return stream === null || stream === void 0 || (_stream$getAudioTrack = stream.getAudioTracks) === null || _stream$getAudioTrack === void 0 ? void 0 : _stream$getAudioTrack.call(stream).length;
					});
					const mixed = media.mixAudio([media.local, ...remoteStreams.values()]);
					if (sources.length && !(mixed === null || mixed === void 0 || (_mixed$getAudioTracks = mixed.getAudioTracks) === null || _mixed$getAudioTracks === void 0 ? void 0 : _mixed$getAudioTracks.call(mixed).length)) throw new Error("三分屏录制需要混入音频，但混音不可用（AudioContext 缺失或被拒绝）：无法录制含声音的存证。请修复后重试，或显式以 { composite: false } 录制原始流");
					mixed === null || mixed === void 0 || (_mixed$getAudioTracks2 = mixed.getAudioTracks) === null || _mixed$getAudioTracks2 === void 0 || _mixed$getAudioTracks2.call(mixed).forEach((track) => compositeStream.addTrack(track));
				}
				return compositeStream;
			} catch (error) {
				try {
					stopComposite();
				} catch (cleanupError) {
					error.message = `${error.message}；合成收尾也失败：${cleanupError.message}`;
				}
				throw error;
			}
		}
		function stopComposite() {
			var _compositeStream$getT;
			const failures = [];
			compositor === null || compositor === void 0 || compositor.stop();
			if (compositeStream) (_compositeStream$getT = compositeStream.getTracks) === null || _compositeStream$getT === void 0 || _compositeStream$getT.call(compositeStream).forEach((track, index) => {
				collectFailures(failures, `停止合成轨道 #${index}`, () => track.stop());
			});
			compositor = null;
			compositeStream = null;
			if (failures.length) throw new Error(`停止画面合成失败：${summarizeFailures(failures)}`);
			return true;
		}
		const record = {
			/**
			* 开始一段录制。
			* @param {{processId?:string,bizNo?:string,nodeName?:string,segmentIndex?:number,
			*          handlerType?:string,handlerName?:string,composite?:boolean,upload?:Function}} meta
			*/
			async start(meta = {}) {
				var _meta$processId, _meta$bizNo, _meta$segmentIndex, _meta$handlerType, _ref2, _meta$handlerName, _meta$nodeName;
				const instance = ensureRecorder(meta.upload);
				state.processId = (_meta$processId = meta.processId) !== null && _meta$processId !== void 0 ? _meta$processId : state.processId;
				state.bizNo = (_meta$bizNo = meta.bizNo) !== null && _meta$bizNo !== void 0 ? _meta$bizNo : state.bizNo;
				let stream;
				if (meta.composite !== false && media.hasDesktop) stream = startComposite();
				else {
					var _surface$source;
					stream = (_surface$source = surface.source("remote")) !== null && _surface$source !== void 0 ? _surface$source : media.local;
				}
				if (!stream) throw new Error("当前没有可录制的媒体流，请先连接音视频");
				const recordingId = await instance.start(stream, {
					...meta,
					processId: state.processId,
					bizNo: state.bizNo,
					segmentIndex: (_meta$segmentIndex = meta.segmentIndex) !== null && _meta$segmentIndex !== void 0 ? _meta$segmentIndex : 2,
					handlerType: (_meta$handlerType = meta.handlerType) !== null && _meta$handlerType !== void 0 ? _meta$handlerType : "HUMAN_AGENT",
					handlerName: (_ref2 = (_meta$handlerName = meta.handlerName) !== null && _meta$handlerName !== void 0 ? _meta$handlerName : options.agentName) !== null && _ref2 !== void 0 ? _ref2 : "远程坐席"
				});
				set({
					recording: true,
					recordingId
				});
				return {
					recordingId,
					processId: state.processId,
					bizNo: state.bizNo,
					nodeName: (_meta$nodeName = meta.nodeName) !== null && _meta$nodeName !== void 0 ? _meta$nodeName : null
				};
			},
			/**
			* 封存：返回含 `fileSize` 的回执。
			* ★ `fileSize === 0` 时如实返回，**业务侧不得宣告存证成功**（§3.4）。
			* ★ 收尾（停合成 / 释放桌面 / 状态回写）放在 finally：封存报错也必须收干净；
			*   收尾自身的失败走 error 事件上报，既不吞掉、也不盖掉封存本身的回执或异常。
			*/
			async seal() {
				const instance = recorder;
				if (!instance) return {
					success: false,
					fileSize: 0,
					message: "当前没有进行中的录制"
				};
				let receipt;
				try {
					receipt = await instance.seal();
				} finally {
					const failures = [];
					await collectFailures(failures, "停止画面合成", () => stopComposite());
					await collectFailures(failures, "释放坐席桌面", () => media.releaseDesktop());
					set({
						recording: false,
						recordingId: null,
						hasDesktop: false
					});
					if (failures.length) emit("error", {
						error: /* @__PURE__ */ new Error(`录制封存收尾未完全成功：${summarizeFailures(failures)}`),
						fatal: false,
						phase: "seal-cleanup"
					});
				}
				return receipt;
			},
			/** 页面重载后的补传：同一 recordingId 的待传分片重新排空。 */
			async resume() {
				var _options$queue2;
				const result = { ...await ((_options$queue2 = options.queue) !== null && _options$queue2 !== void 0 ? _options$queue2 : ensureRecorder().queue).flush() || {} };
				result.fileSize = Number(result.fileSize) || 0;
				result.success = Boolean(result.success) && result.fileSize > 0;
				return result;
			},
			get active() {
				return Boolean(recorder === null || recorder === void 0 ? void 0 : recorder.active);
			}
		};
		/** 按当前模式解析像素盒子；dock 下返回 `{ order, heightRatio, height }`。 */
		function resolveBox(viewport = {}) {
			return resolve(state.mode, state.layout, viewport, state.pixel || {});
		}
		/** 拖拽落位：回写**归一化值**并持久化，不存像素。 */
		function moveTo(box, viewport) {
			if (state.mode === MODE.DOCK) return { ...state.layout };
			const normalized = normalize(box, viewport);
			state.layout = {
				...state.layout,
				...normalized
			};
			persistLayout(state.layout, { storage: options.storage });
			set({ layout: state.layout });
			return { ...state.layout };
		}
		function setLayout(next = {}) {
			if (next != null && (typeof next !== "object" || Array.isArray(next))) throw new Error(`layout 必须是归一化布局对象，收到：${Array.isArray(next) ? "array" : typeof next}`);
			state.layout = mergeLayout(state.layout, next);
			persistLayout(state.layout, { storage: options.storage });
			set({ layout: state.layout });
			return { ...state.layout };
		}
		function restoreLayout$1(fallback) {
			state.layout = restoreLayout(fallback !== null && fallback !== void 0 ? fallback : state.layout, { storage: options.storage });
			set({ layout: state.layout });
			return { ...state.layout };
		}
		/**
		* 质量观测。规格：**永不抛错**（§6.4，统计失败不影响通话），
		* 但拿不到观测值时必须如实标注 `degraded`，不得返回一个"看起来正常"的空壳。
		*/
		function getStats() {
			try {
				var _transport$getStats;
				const stats = transport === null || transport === void 0 || (_transport$getStats = transport.getStats) === null || _transport$getStats === void 0 ? void 0 : _transport$getStats.call(transport);
				if (stats) return stats;
				return {
					state: state.connection,
					degraded: true,
					error: transport ? "当前传输实现未提供观测数据" : "传输尚未建立，观测数据暂缺"
				};
			} catch (error) {
				var _error$message3;
				return {
					state: state.connection,
					degraded: true,
					error: (_error$message3 = error === null || error === void 0 ? void 0 : error.message) !== null && _error$message3 !== void 0 ? _error$message3 : String(error)
				};
			}
		}
		/**
		* 供宿主注入外部已有流（渠道方 SDK 场景）。
		* ★ 不支持注入的传输实现如实报错——静默跳过会让 hasRemote/call 变 true
		*   却没有任何流真正进入链路，上层完全看不出问题。
		*/
		function injectStream(stream, peerId = "injected") {
			const instance = ensureTransport();
			if (typeof instance.injectStream !== "function") throw new Error(`当前传输实现（${instance.name}）不支持注入外部流：请先 controller.useTransport('injected')，或改由传输自行提供远端流`);
			remoteStream = stream;
			surface.attach("remote", stream);
			instance.injectStream(stream, peerId);
			set({
				hasRemote: true,
				call: true,
				peerId
			});
			return peerId;
		}
		return {
			open,
			close,
			prepare,
			connect,
			updateParticipants,
			swapRemote,
			hangup,
			takeover,
			toggle,
			capture: capture$1,
			record,
			on,
			off,
			getState,
			resolveBox,
			moveTo,
			setLayout,
			restoreLayout: restoreLayout$1,
			getStats,
			injectStream,
			useTransport,
			useSurface,
			get transport() {
				return transport;
			},
			get recorder() {
				return recorder;
			},
			get surface() {
				return surface;
			}
		};
	}
	/**
	* 共享单例。业务侧 `import { video } from '@/core/video/controller'` 即可，
	* 与 vendor 前的 stub 用法完全一致，替换时业务侧一行不改。
	*/
	var video = createVideoController();
	//#endregion
	//#region src/stage.js
	/**
	* VideoStage —— 原生 JS 视图层（契约 §2.9：**零框架依赖**，不引 Vue/React）。
	*
	* 职责就三件事：
	*  1. 把 DOM（视频窗 / 占位 / 徽标 / 工具栏 / 错误条）渲染进宿主给的容器；
	*  2. 把 controller 的 state 变成可见的 class / text / style（唯一数据源是 `getState()`）；
	*  3. 把 DOM 事件（拖拽、工具栏点击）翻译回命令 API（moveTo / capture / toggle / hangup）。
	*
	* ★ 与内核的边界：视图层是**唯一**碰 `<video>` DOM 的地方——通过 DomSurface 挂流，
	*   内核只见 surface 接口（§6.4 MediaTransport / surface 抽象）。挂载时把 DomSurface
	*   经 `controller.useSurface()` 换进去；销毁时换回 NullSurface 并清掉 srcObject（§8.4-5）。
	*
	* 布局沿用 `layout.js` 的同一口径：overlay 用归一化→像素换算（拖拽回写归一化值），
	* dock 退化为 order + heightRatio 参与宿主流式布局（§2.9.2）。
	*
	* 样式在 `styles.css`（构建后为 dist/vb-video.css），全部以 `.vb-` 前缀限定，不污染宿主。
	*/
	/** 默认图标（内联 SVG，零依赖零字体）。宿主可用 options.icons 逐个替换为文本或自定义 SVG。 */
	var DEFAULT_ICONS = Object.freeze({
		capture: "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"13\" r=\"3.5\"/><path d=\"M4 8.5h3l1.5-2h7L17 8.5h3v10H4z\"/></svg>",
		audio: "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"9\" y=\"3\" width=\"6\" height=\"11\" rx=\"3\"/><path d=\"M5 11a7 7 0 0 0 14 0M12 18v3\"/></svg>",
		hangup: "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M3.5 14.5c5-5 12-5 17 0l-2.5 2.5-3.5-1.5v-2.2a10.5 10.5 0 0 0-5 0v2.2L6 17z\"/></svg>",
		drag: "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\"><path d=\"M12 4v16M4 12h16M9 7l3-3 3 3M9 17l3 3 3-3M7 9l-3 3 3 3M17 9l3 3-3 3\"/></svg>",
		unlock: "<svg viewBox=\"0 0 24 24\" width=\"20\" height=\"20\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\"><path d=\"M7 10V7a5 5 0 0 1 9.5-2M6 10h12v9H6z\"/><circle cx=\"12\" cy=\"14.5\" r=\"1.4\" fill=\"currentColor\" stroke=\"none\"/></svg>"
	});
	var noop = () => {};
	var VideoStage = class {
		/**
		* @param {{controller:object, toolbar?:boolean, showLocalPreview?:boolean,
		*          zIndex?:number, icons?:Record<string,string>, placeholder?:string,
		*          onCapture?:(blob:Blob|null)=>void, onHangup?:()=>void}} options
		*/
		constructor(options = {}) {
			var _options$zIndex, _options$placeholder, _options$unlockText, _options$onCapture, _options$onHangup, _options$onHangupRequ;
			if (!options.controller) throw new Error("VideoStage 需要 controller（命令 API 实例）");
			this.controller = options.controller;
			this.options = {
				toolbar: options.toolbar !== false,
				showLocalPreview: options.showLocalPreview !== false,
				zIndex: (_options$zIndex = options.zIndex) !== null && _options$zIndex !== void 0 ? _options$zIndex : 2e3,
				icons: {
					...DEFAULT_ICONS,
					...options.icons || {}
				},
				placeholder: (_options$placeholder = options.placeholder) !== null && _options$placeholder !== void 0 ? _options$placeholder : "等待客户接入…",
				unlockText: (_options$unlockText = options.unlockText) !== null && _options$unlockText !== void 0 ? _options$unlockText : "开启画面与声音",
				onCapture: (_options$onCapture = options.onCapture) !== null && _options$onCapture !== void 0 ? _options$onCapture : noop,
				onHangup: (_options$onHangup = options.onHangup) !== null && _options$onHangup !== void 0 ? _options$onHangup : noop,
				onHangupRequest: (_options$onHangupRequ = options.onHangupRequest) !== null && _options$onHangupRequ !== void 0 ? _options$onHangupRequ : null
			};
			this.root = null;
			this.surface = null;
			this.box = null;
			this.viewport = {
				width: 0,
				height: 0
			};
			this.dragging = false;
			this.showUnlock = false;
			this._hadRemote = false;
			this._listeners = /* @__PURE__ */ new Map();
			this._offState = null;
			this._observer = null;
			this._restoreParentPosition = null;
			this._els = {};
			this._drag = null;
			this._handlers = {};
		}
		on(event, handler) {
			if (typeof handler !== "function") return noop;
			if (!this._listeners.has(event)) this._listeners.set(event, /* @__PURE__ */ new Set());
			this._listeners.get(event).add(handler);
			return () => this.off(event, handler);
		}
		off(event, handler) {
			var _this$_listeners$get;
			(_this$_listeners$get = this._listeners.get(event)) === null || _this$_listeners$get === void 0 || _this$_listeners$get.delete(handler);
		}
		emit(event, payload) {
			(this._listeners.get(event) || []).forEach((handler) => {
				try {
					handler(payload);
				} catch (error) {
					console.error(`[video] stage ${event} 监听器异常`, error);
				}
			});
		}
		/**
		* 把控件渲染进宿主容器。只管 DOM 落位，不申请任何媒体权限（§2.9.1）。
		* @param {Element} container
		*/
		mount(container) {
			var _container$ownerDocum, _this$controller$useS, _this$controller;
			if (this.root) return this;
			if (!container || typeof container.appendChild !== "function") throw new Error("mount(container) 需要一个容器元素");
			const doc = (_container$ownerDocum = container.ownerDocument) !== null && _container$ownerDocum !== void 0 ? _container$ownerDocum : globalThis.document;
			if (!doc) throw new Error("VideoStage 只能在浏览器环境挂载（node 里请直接用 controller + NullSurface）");
			this.root = doc.createElement("div");
			this.root.className = "vb-video-stage";
			this.root.style.zIndex = String(this.options.zIndex);
			this.root.setAttribute("data-vb-stage", "");
			this._build(doc);
			container.appendChild(this.root);
			try {
				var _doc$defaultView;
				const computed = (_doc$defaultView = doc.defaultView) === null || _doc$defaultView === void 0 ? void 0 : _doc$defaultView.getComputedStyle(container);
				if (!computed || computed.position === "static") {
					this._restoreParentPosition = container.style.position || "";
					container.style.position = "relative";
				}
			} catch (error) {}
			this.surface = new DomSurface({ document: doc });
			this.surface.bind("remote", this._els.remote);
			this.surface.bind("secondary", this._els.secondary);
			this.surface.bind("local", this._els.local);
			(_this$controller$useS = (_this$controller = this.controller).useSurface) === null || _this$controller$useS === void 0 || _this$controller$useS.call(_this$controller, this.surface);
			this._offState = this.controller.on("state", (state) => {
				this.render(state);
				this.measure();
			});
			const view = doc.defaultView;
			if (typeof (view === null || view === void 0 ? void 0 : view.ResizeObserver) === "function") {
				this._observer = new view.ResizeObserver(() => this.measure());
				this._observer.observe(container);
			}
			this._bindPointer();
			this.render(this.controller.getState());
			this.measure();
			return this;
		}
		/** 卸载并释放：退订、断观察、清 srcObject（§8.4-5）、移除 DOM、还原宿主样式。 */
		destroy() {
			var _this$_offState, _this$_observer, _this$_observer$disco;
			if (!this.root) return false;
			(_this$_offState = this._offState) === null || _this$_offState === void 0 || _this$_offState.call(this);
			this._offState = null;
			(_this$_observer = this._observer) === null || _this$_observer === void 0 || (_this$_observer$disco = _this$_observer.disconnect) === null || _this$_observer$disco === void 0 || _this$_observer$disco.call(_this$_observer);
			this._observer = null;
			Object.entries(this._handlers).forEach(([type, fn]) => {
				var _this$root;
				(_this$root = this.root) === null || _this$root === void 0 || _this$root.removeEventListener(type, fn);
			});
			this._handlers = {};
			if (this.surface) {
				if (this.controller.surface === this.surface) this.controller.useSurface(new NullSurface());
				this.surface.clear();
				this.surface = null;
			}
			const parent = this.root.parentElement;
			this.root.remove();
			this.root = null;
			if (parent && this._restoreParentPosition !== null) {
				parent.style.position = this._restoreParentPosition;
				this._restoreParentPosition = null;
			}
			this._listeners.clear();
			return true;
		}
		_build(doc) {
			const els = this._els = {};
			const make = (tag, className, text) => {
				const el = doc.createElement(tag);
				if (className) el.className = className;
				if (text != null) el.textContent = text;
				return el;
			};
			const iconSpan = (name) => {
				const span = make("span", "vb-stage__icon");
				const svg = this.options.icons[name];
				if (typeof svg === "string" && svg.trimStart().startsWith("<")) span.innerHTML = svg;
				else span.textContent = svg !== null && svg !== void 0 ? svg : "";
				span.setAttribute("aria-hidden", "true");
				return span;
			};
			els.remote = make("video", "vb-stage__video vb-stage__remote");
			els.remote.playsInline = true;
			els.secondary = make("video", "vb-stage__video vb-stage__secondary");
			els.secondary.playsInline = true;
			els.secondary.hidden = true;
			els.placeholder = make("div", "vb-stage__placeholder");
			const phIcon = make("span", "vb-stage__ph-icon");
			phIcon.innerHTML = "<svg viewBox=\"0 0 24 24\" width=\"34\" height=\"34\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\"><rect x=\"3\" y=\"6\" width=\"13\" height=\"12\" rx=\"2\"/><path d=\"M16 10.5l5-3v9l-5-3z\"/></svg>";
			els.phText = make("p", "vb-stage__ph-text", this.options.placeholder);
			els.placeholder.append(phIcon, els.phText);
			els.local = make("video", "vb-stage__video vb-stage__local");
			els.local.muted = true;
			els.local.playsInline = true;
			els.badge = make("div", "vb-stage__badge");
			els.badgeDot = make("i", "vb-stage__dot");
			els.badgeText = make("span", null, "");
			els.badge.append(els.badgeDot, els.badgeText);
			els.rec = make("div", "vb-stage__rec vb-stage__rec--on", "REC");
			els.unlock = make("button", "vb-stage__unlock");
			els.unlock.type = "button";
			els.unlock.append(iconSpan("unlock"), make("span", null, this.options.unlockText));
			els.toolbar = make("div", "vb-stage__toolbar");
			els.toolbar.setAttribute("role", "toolbar");
			els.btnCapture = this._makeButton("capture", "抓拍");
			els.btnAudio = this._makeButton("audio", "静音");
			els.btnHangup = this._makeButton("hangup", "挂断");
			els.btnDrag = this._makeButton("drag", "拖动窗口");
			els.btnDrag.dataset.action = "drag";
			els.toolbar.append(els.btnCapture, els.btnAudio, els.btnHangup, els.btnDrag);
			els.error = make("p", "vb-stage__error");
			this.root.append(els.remote, els.placeholder, els.local, els.secondary, els.badge, els.rec, els.unlock, els.toolbar, els.error);
			this._handlers.action = (event) => {
				var _event$target, _event$target$closest, _this$root2;
				if (event.target === els.secondary) {
					var _this$controller$swap, _this$controller2;
					(_this$controller$swap = (_this$controller2 = this.controller).swapRemote) === null || _this$controller$swap === void 0 || _this$controller$swap.call(_this$controller2);
					return;
				}
				const button = (_event$target = event.target) === null || _event$target === void 0 || (_event$target$closest = _event$target.closest) === null || _event$target$closest === void 0 ? void 0 : _event$target$closest.call(_event$target, "button[data-action]");
				if (!button || !((_this$root2 = this.root) === null || _this$root2 === void 0 ? void 0 : _this$root2.contains(button))) return;
				if (button.dataset.action === "drag") return;
				this._runAction(button.dataset.action);
			};
			this.root.addEventListener("click", this._handlers.action);
			this._handlers.unlock = () => {
				var _this$_els$remote$pla, _this$_els$remote, _this$_els$remote$pla2;
				this._els.remote.muted = false;
				this.showUnlock = false;
				(_this$_els$remote$pla = (_this$_els$remote = this._els.remote).play) === null || _this$_els$remote$pla === void 0 || (_this$_els$remote$pla = _this$_els$remote$pla.call(_this$_els$remote)) === null || _this$_els$remote$pla === void 0 || (_this$_els$remote$pla2 = _this$_els$remote$pla.catch) === null || _this$_els$remote$pla2 === void 0 || _this$_els$remote$pla2.call(_this$_els$remote$pla, () => {
					this.showUnlock = true;
				});
				this.render(this.controller.getState());
			};
			els.unlock.addEventListener("click", this._handlers.unlock);
		}
		_makeButton(action, label) {
			const button = this.root.ownerDocument.createElement("button");
			button.type = "button";
			button.className = `vb-stage__btn vb-stage__btn--${action}`;
			button.dataset.action = action;
			button.title = label;
			button.setAttribute("aria-label", label);
			button.append(this._icon(action));
			return button;
		}
		_icon(name) {
			const span = this.root.ownerDocument.createElement("span");
			span.className = "vb-stage__icon";
			const svg = this.options.icons[name];
			if (typeof svg === "string" && svg.trimStart().startsWith("<")) span.innerHTML = svg;
			else span.textContent = svg !== null && svg !== void 0 ? svg : "";
			span.setAttribute("aria-hidden", "true");
			return span;
		}
		/**
		* state 的唯一消费者。幂等：重复渲染同一 state 不产生副作用。
		* @param {object} state controller.getState() 的快照
		*/
		render(state) {
			if (!this.root) return;
			const els = this._els;
			const dock = state.mode === "dock";
			this.root.classList.toggle("vb-video-stage--dock", dock);
			this.root.classList.toggle("vb-video-stage--overlay", !dock);
			this.root.classList.toggle("is-draggable", Boolean(state.draggable) && !dock);
			this.root.classList.toggle("is-dragging", this.dragging);
			els.placeholder.hidden = Boolean(state.hasRemote);
			const hadRemote = this._hadRemote;
			this._hadRemote = Boolean(state.hasRemote);
			if (!hadRemote && state.hasRemote) this.ensurePlaying();
			if (hadRemote && !state.hasRemote) this.showUnlock = false;
			els.local.hidden = !(this.options.showLocalPreview && state.hasLocal);
			els.secondary.hidden = !state.hasSecondaryRemote;
			els.badge.hidden = !(state.mounted || state.call);
			els.badgeText.textContent = state.usingMockStream ? "演示画面" : state.call ? "通话中" : "待接入";
			els.badge.classList.toggle("vb-badge--live", state.call && !state.usingMockStream);
			els.badge.classList.toggle("vb-badge--mock", Boolean(state.usingMockStream));
			els.rec.hidden = !state.recording;
			els.unlock.hidden = !(this.showUnlock && state.hasRemote);
			els.toolbar.hidden = !(this.options.toolbar && state.mounted);
			els.btnAudio.classList.toggle("is-on", state.audioEnabled === false);
			els.btnAudio.setAttribute("aria-pressed", String(state.audioEnabled === false));
			if (state.error) {
				els.error.hidden = false;
				els.error.textContent = state.error;
			} else {
				els.error.hidden = true;
				els.error.textContent = "";
			}
		}
		/** 按容器尺寸与 state（mode/layout/pixel）换算并落 style。宿主 resize 时自动重算。 */
		measure() {
			var _parent$clientWidth, _parent$clientHeight;
			if (!this.root) return;
			const parent = this.root.parentElement;
			const state = this.controller.getState();
			this.viewport = {
				width: (_parent$clientWidth = parent === null || parent === void 0 ? void 0 : parent.clientWidth) !== null && _parent$clientWidth !== void 0 ? _parent$clientWidth : 0,
				height: (_parent$clientHeight = parent === null || parent === void 0 ? void 0 : parent.clientHeight) !== null && _parent$clientHeight !== void 0 ? _parent$clientHeight : 0
			};
			if (state.mode === "dock") {
				var _dock$order;
				const dock = this.controller.resolveBox(this.viewport);
				this.root.style.removeProperty("left");
				this.root.style.removeProperty("top");
				this.root.style.removeProperty("width");
				this.root.style.order = String((_dock$order = dock.order) !== null && _dock$order !== void 0 ? _dock$order : 0);
				this.root.style.height = dock.height ? `${dock.height}px` : "";
				return;
			}
			if (!this.dragging) this.box = this.controller.resolveBox(this.viewport);
			this.applyBox();
		}
		applyBox() {
			if (!this.root || !this.box) return;
			const { left, top, width, height } = this.box;
			this.root.style.left = `${Math.round(left)}px`;
			this.root.style.top = `${Math.round(top)}px`;
			this.root.style.width = `${Math.round(width)}px`;
			this.root.style.height = `${Math.round(height)}px`;
		}
		_bindPointer() {
			this._handlers.pointerdown = (event) => {
				var _event$target2, _event$target2$closes;
				const state = this.controller.getState();
				if (!state.draggable || state.mode === "dock") return;
				if (event.button !== 0) return;
				if ((_event$target2 = event.target) === null || _event$target2 === void 0 || (_event$target2$closes = _event$target2.closest) === null || _event$target2$closes === void 0 ? void 0 : _event$target2$closes.call(_event$target2, "button")) return;
				event.preventDefault();
				this.dragging = true;
				this._drag = {
					id: event.pointerId,
					x: event.clientX,
					y: event.clientY,
					box: { ...this.box || this.controller.resolveBox(this.viewport) }
				};
				try {
					var _this$root$setPointer, _this$root3;
					(_this$root$setPointer = (_this$root3 = this.root).setPointerCapture) === null || _this$root$setPointer === void 0 || _this$root$setPointer.call(_this$root3, event.pointerId);
				} catch (error) {}
				this.root.classList.add("is-dragging");
			};
			this._handlers.pointermove = (event) => {
				if (!this.dragging || !this._drag || event.pointerId !== this._drag.id) return;
				const next = {
					...this._drag.box,
					left: this._drag.box.left + (event.clientX - this._drag.x),
					top: this._drag.box.top + (event.clientY - this._drag.y)
				};
				this.box = clamp(next, this.viewport);
				this.applyBox();
			};
			this._handlers.pointerup = (event) => {
				if (!this.dragging || !this._drag || event.pointerId !== this._drag.id) return;
				this.dragging = false;
				this._drag = null;
				this.root.classList.remove("is-dragging");
				try {
					var _this$root$releasePoi, _this$root4;
					(_this$root$releasePoi = (_this$root4 = this.root).releasePointerCapture) === null || _this$root$releasePoi === void 0 || _this$root$releasePoi.call(_this$root4, event.pointerId);
				} catch (error) {}
				const saved = this.controller.moveTo({ ...this.box }, this.viewport);
				this.emit("move", saved);
				this.measure();
			};
			this.root.addEventListener("pointerdown", this._handlers.pointerdown);
			this.root.addEventListener("pointermove", this._handlers.pointermove);
			this.root.addEventListener("pointerup", this._handlers.pointerup);
			this.root.addEventListener("pointercancel", this._handlers.pointerup);
		}
		async _runAction(action) {
			try {
				if (action === "capture") {
					const blob = await this.controller.capture("remote");
					this.options.onCapture(blob);
					this.emit("capture", blob);
				} else if (action === "audio") {
					await this.controller.toggle("audio");
					this.render(this.controller.getState());
				} else if (action === "hangup") {
					if (this.options.onHangupRequest) await this.options.onHangupRequest();
					else await this.controller.hangup();
					this.options.onHangup();
					this.emit("hangup");
				}
			} catch (error) {
				this.emit("error", error);
				const state = this.controller.getState();
				if (state.error) this.render(state);
			}
		}
		/** 宿主策略拦截自动播放时出「开启画面与声音」按钮，而不是黑屏。 */
		ensurePlaying() {
			var _this$_els$remote$pla3, _this$_els$remote2;
			if (!this._els.remote) return;
			const promise = (_this$_els$remote$pla3 = (_this$_els$remote2 = this._els.remote).play) === null || _this$_els$remote$pla3 === void 0 ? void 0 : _this$_els$remote$pla3.call(_this$_els$remote2);
			if (promise && typeof promise.catch === "function") promise.catch(() => {
				this.showUnlock = true;
				this.render(this.controller.getState());
			});
		}
	};
	//#endregion
	//#region src/index.js
	var version = "1.0.0";
	/** 本模块挂过的 stage：destroy() 无参时批量卸载。 */
	var mounted = /* @__PURE__ */ new Set();
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
	function mount(container, options = {}) {
		var _options$controller;
		const controller = (_options$controller = options.controller) !== null && _options$controller !== void 0 ? _options$controller : video;
		const stage = new VideoStage({
			...options,
			controller
		});
		stage.mount(container);
		mounted.add(stage);
		return stage;
	}
	/**
	* 卸载视图。
	* @param {VideoStage} [stage] 缺省时卸载本模块挂过的全部 stage
	*/
	function destroy(stage) {
		if (stage) {
			var _stage$destroy;
			(_stage$destroy = stage.destroy) === null || _stage$destroy === void 0 || _stage$destroy.call(stage);
			mounted.delete(stage);
			return true;
		}
		[...mounted].forEach((item) => {
			var _item$destroy;
			return (_item$destroy = item.destroy) === null || _item$destroy === void 0 ? void 0 : _item$destroy.call(item);
		});
		mounted.clear();
		return true;
	}
	var src_default = video;
	//#endregion
	exports.CONNECTION = CONNECTION;
	exports.ChunkQueue = ChunkQueue;
	exports.InjectedTransport = InjectedTransport;
	exports.MODE = MODE;
	exports.MediaTransport = MediaTransport;
	exports.SegmentRecorder = SegmentRecorder;
	exports.TAKEOVER = TAKEOVER;
	exports.TransportEvent = TransportEvent;
	exports.TransportState = TransportState;
	exports.VERSION = version;
	exports.version = version;
	exports.VideoStage = VideoStage;
	exports.createTransport = createTransport;
	exports.createVideoController = createVideoController;
	exports.default = src_default;
	exports.destroy = destroy;
	exports.getDefaultTransportName = getDefaultTransportName;
	exports.hasTransport = hasTransport;
	exports.injectStream = injectStream;
	Object.defineProperty(exports, "layout", {
		enumerable: true,
		get: function() {
			return layout_exports;
		}
	});
	exports.listTransports = listTransports;
	exports.makeRecordingId = makeRecordingId;
	exports.mount = mount;
	exports.registerTransport = registerTransport;
	exports.resetTransports = resetTransports;
	exports.setDefaultTransport = setDefaultTransport;
	exports.unregisterTransport = unregisterTransport;
	exports.video = video;
});
