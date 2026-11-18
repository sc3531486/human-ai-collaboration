/**
 * 流程快照 store：sequence/epoch 单调收敛的唯一判据所在处。
 *
 * `navis-web` 里这套判据埋在 AgentRouter 闭包里，此处提为独立的响应式 store，
 * 让同步层（sync）与路由层（router）共用同一份语义，避免两处各写一遍判据产生漂移。
 */
import { reactive, computed } from 'vue';
import { bus } from '../bus.js';

export const state = reactive({
    processId: '',
    lastSequence: -1,
    epoch: '',
    snapshot: null,
    connection: 'connecting'
});

/** 具名别名：路由与同步层用 flowState 表达「流程状态」，避免与业务 store 的 state 混淆。 */
export const flowState = state;

/** processId 不匹配的一切帧都必须丢弃：广播里没有 agentId 的帧同样适用。 */
export const belongsTo = processId => Boolean(processId) && state.processId === processId;

/**
 * 单调收敛判据（移植自 agent_router.js 的 accept 前半段）。
 *
 * - epoch 变化时重置 lastSequence：换实例后 sequence 从头计数，不能与上一轮比较；
 * - sequence < lastSequence 丢弃；sequence === lastSequence 只有 force（重放同一快照）才接受。
 *
 * @returns {boolean} true 表示该帧是最新的，可以继续走路由。
 */
export function acceptSequence(detail, force = false) {
    if (!detail || !Number.isSafeInteger(detail.sequence)) return false;
    if (state.epoch && detail.epoch !== state.epoch) state.lastSequence = -1;
    state.epoch = detail.epoch;
    if (detail.sequence < state.lastSequence || (detail.sequence === state.lastSequence && !force)) return false;
    state.lastSequence = detail.sequence;
    state.snapshot = detail;
    // 保存状态只用于恢复上下文，服务端快照始终是当前节点的依据。
    bus.emit('agent:state', detail);
    return true;
}

/** 绑定新业务：序号与世代一并复位（对应 AgentRouter.bind）。 */
export function bind(processId) {
    state.processId = processId;
    state.lastSequence = -1;
    state.epoch = '';
    state.snapshot = null;
}

export function setConnection(status) {
    state.connection = status;
    bus.emit('agent:connection', status);
}

export const current = computed(() => state.snapshot);

export default { state, flowState, acceptSequence, bind, setConnection, belongsTo, current };
