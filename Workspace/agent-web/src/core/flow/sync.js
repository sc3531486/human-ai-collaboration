/**
 * 流程绑定与同步：实时通道首发 + HTTP 首次校准 + 断线轮询兜底。
 *
 * 移植自 agent_process.js：
 *  - 每次绑定业务先拉一次全量快照校准（Centrifugo 不回放历史）；
 *  - 通道在线时**完全依赖推送**，不轮询；DISCONNECTED 才起 2s 轮询；
 *  - 帧丢失保护：`generation` 变化丢弃旧响应，防止旧回调覆盖新业务。
 */
import { flow, request } from '../sdk.js';
import { flowState, acceptSequence, setConnection } from './store.js';
import { bus, report } from '../bus.js';

const POLL_INTERVAL = 2000;

let timer = null;
let generation = 0;
let id = '';
let inFlight = false;

async function refresh(force = false) {
    const expected = generation;
    if (!id || inFlight) return null;
    inFlight = true;
    try {
        const result = await flow.state(id);
        // 帧丢失保护：绑定关系已变的话，这次响应属于上一代业务，丢弃。
        if (expected === generation) {
            acceptSequence(result, true);
            return result;
        }
        return null;
    } catch (error) {
        if (expected === generation) setConnection('offline');
        throw error;
    } finally {
        if (expected === generation) inFlight = false;
    }
}
export { refresh };

function startPolling() {
    if (timer) return;
    // 轮询失败继续重试是设计（下一拍自愈），但失败本身必须可见：
    // sticky 避免 2s 定时器在持续故障时刷屏，system:error 仍逐次上报。
    timer = setInterval(() => { refresh().catch(error => report('流程快照轮询', error, { sticky: true })); }, POLL_INTERVAL);
}

function stopPolling() {
    if (timer) { clearInterval(timer); timer = null; }
}

/**
 * 通道上线/掉线由实时层驱动，这里只负责切换取数方式：
 * 上线停轮询、掉线起轮询——通道在线时绝不叠加轮询。
 */
export function onRealtime(event) {
    if (event === 'CONNECTED') {
        if (!id) return;
        stopPolling();
        setConnection('live');
        refresh(true).catch(error => report('流程快照校准（通道上线）', error, { sticky: true }));
    } else if (event === 'DISCONNECTED') {
        if (!id) return;
        setConnection('polling');
        startPolling();
    }
}

export function stop() {
    generation++;
    stopPolling();
    id = '';
    inFlight = false;
}

export function start(processId, { connected = false } = {}) {
    stop();
    id = processId;
    flowState.processId = processId;
    flowState.lastSequence = -1;
    flowState.epoch = '';
    flowState.snapshot = null;
    /* Centrifugo 不回放历史消息，因此每次绑定业务都先拉一次全量快照完成校准。 */
    refresh(true).catch(error => report('流程快照校准（绑定业务）', error, { sticky: true }));
    /* 通道在线时完全依赖推送，仅在不可用时降级为轮询。 */
    if (connected) { stopPolling(); setConnection('live'); }
    else { setConnection('connecting'); }
}

export function isPolling() { return Boolean(timer); }

export default { start, stop, refresh, onRealtime, isPolling };
