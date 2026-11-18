/**
 * BankSDK：统一接口、提交契约、幂等重试、会话与登记查询。
 *
 * 移植自 `navis-web/public/js/bank-sdk.js`，逐条保留带业务原因的边界：
 *  - request 的错误判定：`!response.ok || data.success === false` 都抛错，消息取 message || error；
 *  - submit 的幂等请求号：同一 `[processId, input]` 签名复用同一 requestId，
 *    仅 4xx 才清除（业务校验失败可改后重提；网络/服务异常保留编号便于查证）。
 */
import { bus } from './bus.js';
import { identity } from './identity.js';

const pending = new Map();

// crypto.randomUUID 仅在安全上下文（HTTPS / localhost）可用；坐席经局域网 IP 走 HTTP 时不存在，
// 因此自带 UUID v4 兜底实现。
export function newRequestId() {
    const crypto = globalThis.crypto;
    if (crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0;
        return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
}

export async function request(url, payload) {
    const response = await fetch(url, payload === undefined ? { credentials: 'same-origin' } : {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': identity.csrfToken || '' }, body: JSON.stringify(payload)
    });
    let data;
    try { data = await response.json(); }
    catch (error) {
        // 响应体不是合法 JSON：把 HTTP 状态带进错误，调用方与 toast 才能定位故障。
        const parseError = new Error(`服务返回异常（HTTP ${response.status}，响应不是合法 JSON）`);
        parseError.status = response.status;
        parseError.code = `HTTP_${response.status}`;
        throw parseError;
    }
    if (!response.ok || data.success === false) {
        if (response.status === 401 && !url.endsWith('/agent-login')) bus.emit('auth:expired');
        const error = new Error(data.message || data.error || '业务操作未完成');
        error.status = response.status;
        // HTTP 层失败给 HTTP_ 码；HTTP 200 但 success===false 是业务码，两者不能混为一谈。
        error.code = response.ok ? `BIZ_${data.code || 'FAIL'}` : `HTTP_${response.status}`;
        throw error;
    }
    return data;
}

const path = id => `/api/platform/process/${encodeURIComponent(id)}`;

export const scenes = { list: () => request('/api/platform/scenes') };

export const sessions = {
    start: context => request('/api/platform/process', context),
    queue: agentId => request(`/api/platform/queue?agentId=${encodeURIComponent(agentId)}`),
    claim: (id, context) => request(`${path(id)}/accept`, context)
};

export const flow = {
    state: id => request(path(id)),
    trail: id => request(`${path(id)}/trail`),
    submissions: id => request(`${path(id)}/submissions`),
    phaseTrace: (bizNo, afterId, limit = 100, segmentIndex = null) =>
        request(`/api/evidence/${segmentIndex === null ? 'trace' : 'phase-trace'}?bizNo=${encodeURIComponent(bizNo)}&afterId=${afterId}&limit=${limit}${segmentIndex === null ? '' : '&segmentIndex=' + segmentIndex}`),
    async submit(state, values = {}, actor = {}, action = state?.nodePolicy?.action) {
        if (!state?.nodePolicy?.action) throw new Error('当前节点没有可提交的操作');
        const input = {
            nodeId: state.nodeId, taskId: state.taskId || null, executionId: state.executionId || null,
            expectedRevision: state.revision ?? null, action,
            ...actor, payload: values
        };
        const signature = JSON.stringify([state.processId, input]);
        // 结果不明时保留同一请求编号；重试不会重复登记或重复推进。
        if (!pending.has(signature)) pending.set(signature, newRequestId());
        input.requestId = pending.get(signature);
        bus.emit('bank:submit-start', { processId: state.processId, action: input.action, state });
        try {
            const result = await request(`/api/platform/processes/${encodeURIComponent(state.processId)}/submit`, input);
            pending.delete(signature);
            bus.emit('bank:submit-success', { ...result, state });
            return result;
        } catch (error) {
            // 业务校验失败可修改后重提；网络或服务异常保留编号以便查证。
            if (error.status >= 400 && error.status < 500) pending.delete(signature);
            bus.emit('bank:submit-error', { error, requestId: input.requestId, state });
            throw error;
        }
    }
};

/** 带 traceContext 的提交：把业务上下文交给留痕模块落账。 */
export function submitWithTrace(state, values, options = {}) {
    const traceContext = options.traceContext || {
        bizNo: state.bizNo, processId: state.processId, nodeName: state.nodeName,
        actorId: identity.agentId, actorRole: 'AGENT', request: options.action || state.nodePolicy?.action
    };
    return flow.submit(state, values, { agentId: identity.agentId }, options.action ?? state.nodePolicy?.action)
        .then(result => {
            bus.emit('trace:context', { type: 'SUBMISSION_ACCEPTED', traceContext, result });
            return result;
        })
        .catch(error => {
            bus.emit('trace:context', { type: 'SUBMISSION_FAILED', traceContext, error });
            throw error;
        });
}

export const evidence = {
    list: async bizNo => {
        const result = await request(`/api/evidence/trace?bizNo=${encodeURIComponent(bizNo)}&afterId=0&limit=200`);
        // rows 是接口的确定字段（缺失即契约漂移）：如实抛出，不拿空数组冒充"无留痕"。
        if (!Array.isArray(result.rows)) throw new Error('留痕接口未返回 rows 列表');
        return result.rows;
    },
    trace: (bizNo, afterId = 0, limit = 100) =>
        request(`/api/evidence/trace?bizNo=${encodeURIComponent(bizNo)}&afterId=${afterId}&limit=${limit}`),
    photo: async (state, fieldName, blob, actor) => {
        const body = new FormData();
        body.append('file', blob, 'photo.png');
        body.append('processId', state.processId);
        body.append('fieldName', fieldName);
        body.append('actorId', actor.agentId || actor.clientId);
        body.append('actorRole', actor.agentId ? 'AGENT' : 'CUSTOMER');
        body.append('eventId', newRequestId());
        const response = await fetch('/api/evidence/photo', { method: 'POST', headers: { 'X-CSRF-Token': identity.csrfToken || '' }, body });
        let data;
        try { data = await response.json(); }
        catch (error) {
            const parseError = new Error(`影像保存失败（HTTP ${response.status}，响应不是合法 JSON）`);
            parseError.status = response.status;
            parseError.code = `HTTP_${response.status}`;
            throw parseError;
        }
        if (!response.ok || data.success === false) {
            const error = new Error(data.message || data.error || '影像保存失败');
            error.status = response.status;
            error.code = response.ok ? `BIZ_${data.code || 'FAIL'}` : `HTTP_${response.status}`;
            throw error;
        }
        return data;
    }
};

export const signaling = { update: (id, context) => request(`${path(id)}/call`, context) };

export default { request, newRequestId, scenes, sessions, flow, submitWithTrace, evidence, signaling };
