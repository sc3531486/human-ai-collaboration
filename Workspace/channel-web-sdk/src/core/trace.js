/*
 * 操作留痕：先落本地待发，失败不丢弃，重试沿用同一 eventId（源：bank-trace.js）。
 * 订阅内核提交事件，不再监听 window CustomEvent。
 */
import { bus, EVENTS } from './bus.js';
import { request, newRequestId } from './sdk.js';

const KEY = 'vb.banking.trace.pending.v1';
let sending = null;

function read() {
    try {
        return JSON.parse(localStorage.getItem(KEY) || '[]');
    } catch {
        return [];
    }
}

function write(rows) {
    try {
        localStorage.setItem(KEY, JSON.stringify(rows));
    } catch {
        /* 隐私模式下 localStorage 不可用，留痕降级为不持久化 */
    }
}

export async function flush() {
    if (sending) return sending;
    sending = (async () => {
        while (true) {
            const rows = read();
            if (!rows.length) return;
            const item = rows[0];
            await request('/api/evidence/trace', item);
            write(read().filter((row) => row.eventId !== item.eventId));
        }
    })();
    try {
        await sending;
    } finally {
        sending = null;
    }
}

export function record(context, type, payload, role = 'CUSTOMER') {
    const item = {
        ...context,
        eventId: newRequestId(),
        actorRole: role,
        eventType: type,
        payload,
        clientTime: new Date().toISOString(),
    };
    write(read().concat([item]));
    return flush();
}

bus.on(EVENTS.SUBMIT_START, (detail) => {
    if (!detail?.processId) return;
    record({ processId: detail.processId, nodeName: detail.nodeName, actorId: detail.actorId }, 'SUBMISSION_STARTED',
        { request: detail.requestId }, detail.actorRole).catch(() => {});
});
bus.on(EVENTS.SUBMIT_SUCCESS, (result) => {
    if (!result?.processId) return;
    record({ processId: result.processId, nodeName: result.nodeName, actorId: result.actorId }, 'SUBMISSION_ACCEPTED',
        { resultingNode: result.nodeName, revision: result.revision }, result.actorRole).catch(() => {});
});
bus.on(EVENTS.SUBMIT_ERROR, (detail) => {
    if (!detail?.processId) return;
    record({ processId: detail.processId, nodeName: detail.nodeName, actorId: detail.actorId }, 'SUBMISSION_FAILED',
        { request: detail.requestId, error: detail.error?.message }, detail.actorRole).catch(() => {});
});

if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    window.addEventListener('online', () => {
        flush().catch(() => {});
    });
}

export const trace = {
    id: newRequestId,
    record,
    flush,
    get pendingCount() {
        return read().length;
    },
};

export default trace;
