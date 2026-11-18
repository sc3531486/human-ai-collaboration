/*
 * 实时通道首发 + HTTP 首次校准 + 断线轮询兜底（契约 §3.3）。
 *   - 通道在线时完全依赖推送，不轮询；DISCONNECTED 才起 3s（移动）轮询
 *   - 每次绑定业务先拉一次全量快照校准（STOMP 不回放历史）
 *   - generation 变化则丢弃旧响应，防止旧回调覆盖新业务
 */
import { flow as sdkFlow } from '../sdk.js';
import { accept, bind, isBound, setConnection, state } from './store.js';
import { connect as stompConnect, disconnect as stompDisconnect } from '../realtime/stomp.js';

const POLL_INTERVAL = 3000;

let generation = 0;
let pollTimer = null;
let held = false;             // 对话期间暂停轮询，避免旧快照覆盖聊天带回的表单
let connection = 'connecting';
let clientId = '';
let onSnapshot = () => {};
let onFailure = () => {};

function stopPolling() {
    if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}

function startPolling() {
    if (held || pollTimer) return;
    pollTimer = setInterval(() => {
        poll().catch(() => {});
    }, POLL_INTERVAL);
}

/** 单次全量快照校准：generation 不匹配的旧响应直接丢弃。 */
export async function poll() {
    const expected = generation;
    const id = state.processId;
    if (!id) return null;
    const snapshot = await sdkFlow.state(id);
    if (expected !== generation) return null;
    if (accept(snapshot, true)) onSnapshot(snapshot);
    return snapshot;
}

export const sync = {
    /** 已订阅的信令频道同样接收流程指定的 NODE_STATE；与 STOMP 通过 sequence 去重。 */
    acceptPush(snapshot) {
        if (!isBound() || snapshot?.processId !== state.processId) return;
        if (accept(snapshot)) onSnapshot(snapshot);
    },
    /**
     * 绑定业务：先 HTTP 全量校准，再起实时通道。
     * processId 可为空（会话尚未创建流程）——此时只建通道，等首个流程号经 setProcess() 绑定。
     */
    async start(options) {
        generation += 1;
        stopPolling();
        held = false;
        clientId = options.customerId;
        onSnapshot = options.onState;
        onFailure = options.onError || (() => {});
        bind(options.processId || '');
        setConnection('connecting');
        connection = 'connecting';

        const expected = generation;
        if (options.processId) {
            try {
                const snapshot = await sdkFlow.state(options.processId);
                if (expected !== generation) return;
                if (accept(snapshot, true)) onSnapshot(snapshot);
            } catch (error) {
                onFailure(error);
            }
        }

        stompConnect({
            customerId: options.customerId,
            onState: (snapshot) => {
                if (accept(snapshot)) onSnapshot(snapshot);
            },
            onConnection: (status) => {
                connection = status;
                if (status === 'live') {
                    setConnection('live');
                    stopPolling();          // 通道在线时完全依赖推送，不轮询
                } else if (status === 'polling') {
                    setConnection('polling');
                    startPolling();         // 明确断线后才起 3s 轮询
                } else {
                    setConnection('connecting');
                    stopPolling();
                }
            },
            onReconnect: () => {
                poll().catch(() => {});
            },
        });
    },

    /** 会话中拿到流程号（首条意图识别返回 processId）：绑定 + 立即校准。 */
    async setProcess(processId) {
        if (!processId) return;
        if (processId === state.processId && isBound()) return;
        bind(processId);
        await poll();
    },

    /** 对话进行中暂停轮询（navis sendMsg 同口径）。 */
    hold() {
        held = true;
        stopPolling();
    },

    /** 表单接管后恢复轮询；通道在线时不启动轮询。 */
    resume() {
        held = false;
        if (connection === 'polling') startPolling();
    },

    stop() {
        generation += 1;
        stopPolling();
        held = false;
        stompDisconnect();
        setConnection('idle');
        connection = 'idle';
        bind('');
        clientId = '';
    },

    get clientId() {
        return clientId;
    },

    poll,
};

export default sync;
