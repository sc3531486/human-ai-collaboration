/*
 * 统一接口封装。移植自 navis-web/public/js/bank-sdk.js，语义逐条对齐（契约 §3.1）：
 *   - request()：!ok || success === false 都抛错，消息取 message || error
 *   - flow.submit()：幂等请求号；仅 4xx 清除编号（业务校验失败可改后重提，网络异常保留便于查证）
 *   - 提交前后派发内核事件，供留痕模块消费
 * 场景组件内禁止直接 fetch（契约 §2.2）。
 */
import { bus, EVENTS } from './bus.js';

let baseUrl = '';

/** SDK 可配置后端地址：缺省空串＝同源（经渠道方或 Vite 反代）。 */
export function configure(options = {}) {
    baseUrl = String(options.apiBase || '').replace(/\/+$/, '');
}

const url = (path) => `${baseUrl}${path}`;

/** 供录像上传等非 JSON 请求复用渠道配置的 API 基址。 */
export const resolveApiUrl = (path) => url(path);

/** crypto.randomUUID 仅在安全上下文可用；HTTP 集成场景需自带 UUID v4 兜底（同 navis-web）。 */
export function newRequestId() {
    const crypto = typeof globalThis !== 'undefined' ? globalThis.crypto : null;
    if (crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
        const random = (Math.random() * 16) | 0;
        return (char === 'x' ? random : (random & 0x3) | 0x8).toString(16);
    });
}

export async function request(path, payload, init) {
    const response = await fetch(url(path), init || (payload === undefined
        ? {}
        : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }));
    let data;
    try {
        data = await response.json();
    } catch {
        throw new Error('服务返回异常，请稍后重试');
    }
    if (!response.ok || data.success === false) {
        const error = new Error(data.message || data.error || '业务操作未完成');
        error.status = response.status;
        throw error;
    }
    return data;
}

const processPath = (id) => `/api/platform/process/${encodeURIComponent(id)}`;
const pending = new Map();

export const scenes = { list: () => request('/api/platform/trades') };

export const flow = {
    state: (id) => request(processPath(id)),
    trail: (id) => request(`${processPath(id)}/trail`),
    submissions: (id) => request(`${processPath(id)}/submissions`),

    async submit(state, values = {}, actor = {}, action) {
        const resolvedAction = action || state.nodePolicy?.action;
        if (!state?.nodePolicy?.action) throw new Error('当前节点没有可提交的操作');
        const input = {
            nodeId: state.nodeId,
            taskId: state.taskId || null,
            executionId: state.executionId || null,
            expectedRevision: state.revision ?? null,
            action: resolvedAction,
            ...actor,
            payload: values,
        };
        const signature = JSON.stringify([state.processId, input]);
        if (!pending.has(signature)) pending.set(signature, newRequestId());
        input.requestId = pending.get(signature);
        bus.emit(EVENTS.SUBMIT_START, { processId: state.processId, nodeName: state.nodeName, action: input.action, requestId: input.requestId });
        try {
            const result = await request(`/api/platform/processes/${encodeURIComponent(state.processId)}/submit`, input);
            pending.delete(signature);
            bus.emit(EVENTS.SUBMIT_SUCCESS, result);
            return result;
        } catch (error) {
            if (error.status >= 400 && error.status < 500) pending.delete(signature);
            bus.emit(EVENTS.SUBMIT_ERROR, { error, requestId: input.requestId, processId: state.processId, nodeName: state.nodeName });
            throw error;
        }
    },
};

/** 节点提交的场景组件入口（契约 §2.2 要求组件内禁止直接 fetch）。 */
export const submitNode = (state, values, actor = {}, action) => flow.submit(state, values, actor, action);

export const evidence = {
    /** 留痕游标：CHAT_AGENT_MESSAGE 等由坐席下发的消息经此处补拉。 */
    async trace(bizNo, afterId = 0, limit = 100) {
        const result = await request(`/api/evidence/trace?bizNo=${encodeURIComponent(bizNo)}&afterId=${encodeURIComponent(afterId)}&limit=${encodeURIComponent(limit)}`);
        return result.rows || [];
    },
    async photo(state, fieldName, blob, actor, eventId) {
        const body = new FormData();
        body.append('file', blob, 'photo.png');
        body.append('processId', state.processId);
        body.append('fieldName', fieldName);
        body.append('actorId', actor.agentId || actor.clientId || '');
        body.append('actorRole', actor.agentId ? 'AGENT' : 'CUSTOMER');
        body.append('eventId', eventId || newRequestId());
        const response = await fetch(url('/api/evidence/photo'), { method: 'POST', body });
        const data = await response.json();
        if (!response.ok || data.success === false) throw new Error(data.message || data.error || '影像保存失败');
        return data;
    },
};

export const signaling = {
    update: (id, context) => request(`${processPath(id)}/call`, context),
};

/** 动作日志：与 navis-web reportAction 同字段。 */
export async function logAction(actionType, actionTitle, payload, bizNo) {
    const body = {
        bizNo: bizNo || '',
        actionType,
        actionTitle,
        payload: typeof payload === 'string' ? payload : JSON.stringify(payload ?? {}),
        clientTime: new Date().toLocaleString(),
    };
    try {
        return await request('/api/balance/action/log', body);
    } catch (error) {
        console.warn('[action/log] 上报失败:', error);
        return null;
    }
}

/** 数字人对话：返回 reply / action / state / payload / proposedAction。 */
export const chat = (payload) => request('/api/balance/ai/chat', payload);
export const humanChat = (payload) => request('/api/evidence/chat', { ...payload, actorRole: 'CUSTOMER' });

/** 数字人配置：姓名/徽标/快捷问法/欢迎语一律由服务端下发，不硬编码（契约 §3.6）。 */
export const aiConfig = () => request('/api/balance/ai/config');
