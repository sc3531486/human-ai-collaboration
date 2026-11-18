/**
 * 节点提交：场景组件的唯一出口（契约 §2.2 —— 组件内禁止直接 fetch）。
 *
 * 带业务原因的边界：
 *  - 幂等 requestId 由 sdk.flow.submit 负责，同一签名复用同一编号；
 *  - 提交成功后立即把服务端快照强灌回路由（force=true），让界面在同一帧内前进到下一节点，
 *    而不是等下一次推送——推送延迟会让坐席以为提交没生效而重复点击。
 */
import { flow, request } from '../sdk.js';
import { identity } from '../identity.js';
import * as router from './router.js';
import { report } from '../bus.js';

/**
 * 提交当前节点。
 * @param {object} state 流程快照（只读）
 * @param {object} values payload
 * @param {{action?:string}} [options]
 */
export async function submitNode(state, values = {}, options = {}) {
    if (!state) throw new Error('请先绑定业务');
    const result = await flow.submit(state, values, { agentId: identity.agentId }, options.action ?? state.nodePolicy?.action);
    await settle(state, result);
    return result;
}

/** 把服务端返回的最新快照推回路由，失败或未推进时如实上报。 */
export async function settle(state, result) {
    if (result?.processId !== state?.processId) return result;
    // force=true：同一快照重放也要接纳，确保提交后界面立刻前进。
    if (router.accept(result, true)) return result;
    // 提交回执缺 sequence/epoch 或落后于已推送帧（§3.2 丢弃过期帧属正常）：
    // 用一次全量快照校准界面。任何一步失败或仍未推进都必须可见——界面停在旧节点
    // 而服务端已前进，是最难被坐席发现的故障，不允许静默。
    const latest = await request(`/api/platform/process/${encodeURIComponent(state.processId)}`);
    if (!router.accept(latest, true)) {
        report('提交后界面刷新', new Error(`全量快照仍未推进界面（sequence=${latest?.sequence ?? '缺失'}，epoch=${latest?.epoch ?? '缺失'}）`));
    }
    return result;
}

/** 非提交流指令（call 模式、接管形态等）统一入口。 */
export async function command(state, action, values = {}) {
    if (!state) throw new Error('请先绑定业务');
    const result = await request(`/api/platform/process/${encodeURIComponent(state.processId)}/${action}`, {
        agentId: identity.agentId, ...values
    });
    await settle(state, result);
    return result;
}

/** 认领业务：队列里的工单没有振铃来电时走这条路。 */
export async function claim(state) {
    const latest = await request(`/api/platform/process/${encodeURIComponent(state.processId)}/accept`, {
        agentId: identity.agentId, peerId: identity.peerId
    });
    await settle(state, latest);
    return latest;
}

export default { submitNode, command, claim, settle };
