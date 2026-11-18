/*
 * 流程快照 store（契约 §3.2 / §3.3）。原生实现，不依赖任何框架（§8.1 禁 Vue）。
 *
 * accept() 的单调收敛判据与 navis-web agent_router.js 逐条一致：
 *   processId 不匹配 → 丢弃；epoch 变化 → 重置 lastSequence；
 *   sequence < lastSequence → 丢弃；sequence === lastSequence && !force → 丢弃。
 * 这样"实时通道首发 + HTTP 首次校准 + 断线轮询兜底"三条来源不会互相覆盖。
 *
 * 视频状态与本切片分离（契约 §8.4-4）：徽标/通话变化不得触发业务区重渲染。
 */

export const state = {
    processId: '',
    snapshot: null,
    /** 当前渲染的节点键（nodeName 优先），供端壳与步骤条消费。 */
    nodeKey: '',
    lastSequence: -1,
    epoch: '',
    completed: false,
    unmatched: false,
    connection: 'idle',
};

const subscribers = new Set();

function notify() {
    const snapshot = Array.from(subscribers);
    for (const fn of snapshot) {
        try {
            fn(state);
        } catch (error) {
            console.warn('[flow/store] 订阅回调异常:', error);
        }
    }
}

/** 订阅快照变化；返回退订函数。 */
export function subscribe(fn) {
    subscribers.add(fn);
    return () => subscribers.delete(fn);
}

let bound = false;

export function bind(processId) {
    state.processId = processId || '';
    state.snapshot = null;
    state.nodeKey = '';
    state.lastSequence = -1;
    state.epoch = '';
    state.completed = false;
    state.unmatched = false;
    bound = Boolean(state.processId);
    notify();
}

export function accept(detail, force = false) {
    if (!detail || typeof detail.sequence !== 'number' || !isFinite(detail.sequence)) return false;
    if (state.processId && detail.processId && detail.processId !== state.processId) return false;
    if (!state.processId && detail.processId) state.processId = detail.processId;
    if (state.epoch && detail.epoch && detail.epoch !== state.epoch) state.lastSequence = -1;
    if (detail.epoch) state.epoch = detail.epoch;
    if (detail.sequence < state.lastSequence) return false;
    if (detail.sequence === state.lastSequence && !force) return false;
    state.lastSequence = detail.sequence;
    state.snapshot = detail;
    state.completed = detail.processStatus === 'COMPLETED';
    notify();
    return true;
}

export function setNodeKey(key) {
    const next = key || '';
    if (state.nodeKey === next) return;
    state.nodeKey = next;
    notify();
}

export function setUnmatched(value) {
    const next = Boolean(value);
    if (state.unmatched === next) return;
    state.unmatched = next;
    notify();
}

export function setConnection(value) {
    if (state.connection === value) return;
    state.connection = value;
    notify();
}

export const isBound = () => bound;
export const snapshot = () => state.snapshot;

export const store = { state, subscribe, bind, accept, setNodeKey, setUnmatched, setConnection, snapshot };
export default store;
