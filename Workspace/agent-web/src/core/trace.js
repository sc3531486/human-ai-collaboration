/**
 * BankTrace：操作先保留本地待发回执；失败不丢弃，重试沿用事件编号。
 *
 * 移植自 `navis-web/public/js/bank-trace.js`：
 *  - 提交前后自动记录 SUBMISSION_STARTED / ACCEPTED / FAILED；
 *  - 本地队列串行 flush，失败留在队列里下一次补发（含页面重载后的 online 事件触发）；
 *  - 「重试沿用 eventId」是幂等的关键，去重发生在服务端而不是靠前端丢弃。
 */
import { request, newRequestId } from './sdk.js';
import { bus, report } from './bus.js';

const KEY = 'bank.trace.pending.v1';

let sending = null;

const pending = () => {
    try {
        const raw = localStorage.getItem(KEY) || '[]';
        const parsed = JSON.parse(raw);
        if (!Array.isArray(parsed)) throw new Error('留痕待发队列不是数组');
        return parsed;
    } catch (error) {
        // 队列损坏/存储不可用时不许静默丢弃（那会连带覆盖掉既有留痕）：先原样备份留证，
        // 再带码上报；本队列从空重建，后续补发继续工作。
        try { localStorage.setItem(`${KEY}.corrupt`, localStorage.getItem(KEY) || ''); }
        catch (backupError) { report('留痕队列备份', backupError, { sticky: true }); }
        report('留痕待发队列解析', error, { sticky: true });
        return [];
    }
};

async function flush() {
    if (sending) return sending;
    sending = (async () => {
        while (pending().length) {
            const item = pending()[0];
            await request('/api/evidence/trace', item);
            localStorage.setItem(KEY, JSON.stringify(pending().filter(event => event.eventId !== item.eventId)));
        }
    })();
    try { await sending; } finally { sending = null; }
}

function record(context, type, payload, role = 'CUSTOMER') {
    const item = {
        ...context,
        eventId: newRequestId(),
        actorRole: role,
        eventType: type,
        payload,
        clientTime: new Date().toISOString()
    };
    localStorage.setItem(KEY, JSON.stringify([...pending(), item]));
    return flush();
}

if (typeof window !== 'undefined') {
    // 补发失败不阻断（队列保留待下一次），但必须可见：sticky 防 online 事件/循环补发刷屏。
    window.addEventListener('online', () => flush().catch(error => report('留痕补发（网络恢复）', error, { sticky: true })));
    // 待发回执未落账就关页面会丢留痕，此处必须拦一下。
    window.addEventListener('beforeunload', event => {
        if (pending().length) { event.preventDefault(); event.returnValue = ''; }
    });
}

bus.on('bank:submit-start', ({ processId, action, state }) => {
    if (!state?.bizNo) return;
    record({ bizNo: state.bizNo, processId, nodeName: state.nodeName, actorId: state.actorId },
        'SUBMISSION_STARTED', { request: action }, 'AGENT')
        .catch(error => report('留痕记录（提交开始）', error, { sticky: true }));
});
bus.on('bank:submit-success', detail => {
    const state = detail?.state;
    if (!state?.bizNo) return;
    record({ bizNo: state.bizNo, processId: state.processId, nodeName: state.nodeName, actorId: state.actorId },
        'SUBMISSION_ACCEPTED', { resultingNode: detail.nodeName, revision: detail.revision }, 'AGENT')
        .catch(error => report('留痕记录（提交成功）', error, { sticky: true }));
});
bus.on('bank:submit-error', ({ error, requestId, state }) => {
    if (!state?.bizNo) return;
    record({ bizNo: state.bizNo, processId: state.processId, nodeName: state.nodeName, actorId: state.actorId },
        'SUBMISSION_FAILED', { error: error?.message, requestId }, 'AGENT')
        .catch(error => report('留痕记录（提交失败）', error, { sticky: true }));
});

export const trace = { id: newRequestId, record, flush, get pendingCount() { return pending().length; } };
export default trace;
