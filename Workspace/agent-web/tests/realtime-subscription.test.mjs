/*
 * 坐席端实时订阅回归：NODE_STATE 与 AGENT_RELEASED 必须被消费。
 *
 * 背景（真实故障）：坐席 `init()` 注册了 CHAT_MESSAGE / CALL_RINGING / CALL_ANSWERED /
 * QUEUE_UPDATED / QUEUE_SYNC / CONNECTED / DISCONNECTED，唯独漏了流程节点推送。
 * 而通道在线时轮询被完全停掉（sync.onRealtime('CONNECTED') → stopPolling），
 * 推送是唯一的状态来源 —— 于是客户提交签名、流程已到「人工核实签名」，
 * 坐席界面却永远停在「人工授权」。
 *
 * 本测试直接从源码断言订阅契约，不看运行时实现细节。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { ref, computed } from 'vue';

const STORE = fileURLToPath(new URL('../src/stores/workspace.js', import.meta.url));
const source = readFileSync(STORE, 'utf8');

function workspaceHarness(request) {
    const cleanup = [];
    const sandbox = {
        defineStore: (_name, factory) => factory, ref, computed, request,
        identity: { agentId: 'agent1' }, bus: { emit() {} }, report() {},
        flowState: {}, setConnection() {}, trace: {},
        realtime: { ready: async () => {} },
        video: { getState: () => ({ recording: false }), prepare: async () => {},
            unmount: async () => { cleanup.push('unmount'); }, close: async () => {} },
        setInterval: () => 1, clearInterval() {}, Date,
    };
    const executable = source.replace(/^import .*;\r?$/gm, '')
        .replace('export const useWorkspaceStore =', 'globalThis.createWorkspace =');
    runInNewContext(executable, sandbox);
    return { store: sandbox.createWorkspace(), cleanup, sandbox };
}

for (const status of [404, 409]) {
    test(`路由接听 ${status} 必须失败并清理，不改走流程认领`, async () => {
        const calls = [];
        const error = Object.assign(new Error('接听拒绝'), { status });
        const { store, cleanup } = workspaceHarness(async url => {
            calls.push(url);
            if (url === '/api/media/users') return { peerId: 'bank1' };
            throw error;
        });
        await assert.rejects(store.answerCall('p1', true), failure => failure === error);
        assert.deepEqual(calls, ['/api/media/users', '/api/platform/call/p1/answer']);
        assert.deepEqual(cleanup, ['unmount']);
        assert.equal(store.business.value, null);
    });
}

test('流程待办不再伪装成路由振铃', async () => {
    const { store } = workspaceHarness(async () => ({
        queue: { queue: [{ processId: 'waiting1', status: 'WAITING' }], ringing: null },
        flownodes: [{ processId: 'phantom1' }],
    }));
    store.online.value = true;
    await store.refreshQueue();
    assert.equal(store.ringing.value, null);
    assert.equal(store.queue.value[0].processId, 'waiting1');
});

test('视频接入失败必须停止接听，不能继续绑定业务', async () => {
    const { store, sandbox, cleanup } = workspaceHarness(async url => {
        if (url === '/api/media/users') return { peerId: 'bank1' };
        if (url.startsWith('/api/calls/by-process/')) return { callId: 'call1', status: 'OPEN', processId: 'p1' };
        return { processId: 'p1', vars: { mobile_peer_id: 'cust1' } };
    });
    sandbox.video.open = async () => {};
    sandbox.video.connect = async () => { throw new Error('peer-unavailable'); };
    await assert.rejects(store.answerCall('p1'), /音视频接入失败/);
    assert.equal(store.business.value, null);
    assert.deepEqual(cleanup, ['unmount']);
});

test('只有路由实际分配的振铃进入横条', async () => {
    const ticket = { processId: 'p1', ticketId: 't1', status: 'RINGING', agentId: 'agent1' };
    const { store } = workspaceHarness(async () => ({ queue: { queue: [ticket], ringing: ticket } }));
    store.online.value = true;
    await store.refreshQueue();
    assert.equal(store.ringing.value.processId, 'p1');
});

/** 取出 init() 里 realtime.connect(...) 的链式订阅体。 */
function subscriptionBlock() {
    const at = source.indexOf('realtime.connect(');
    assert.ok(at >= 0, 'workspace.js 里应能定位到 realtime.connect(...)');
    const end = source.indexOf('await Promise.all(', at);
    return source.slice(at, end > at ? end : at + 4000);
}

test('坐席端必须订阅 NODE_STATE，否则节点推进不会刷新界面', () => {
    const block = subscriptionBlock();
    assert.match(
        block,
        /\.on\(\s*'NODE_STATE'/,
        "坐席 init() 必须 .on('NODE_STATE', ...)；通道在线时不轮询，漏订阅会让坐席界面" +
        '停在旧节点（客户提交签名后看不到"人工核实签名"）',
    );
});

test('NODE_STATE 处理必须经 router.accept 走同一套序号收敛判据', () => {
    const at = source.indexOf(".on('NODE_STATE'");
    const body = source.slice(at, at + 600);
    assert.match(
        body,
        /router\.accept\(/,
        'NODE_STATE 必须交给 router.accept：它负责 processId 归属、sequence/epoch 单调收敛，' +
        '自己写一份判据会与 sync.refresh 的判据漂移',
    );
});

test('坐席端必须处理 AGENT_RELEASED（保留坐席的最后人工阶段完成）', () => {
    const block = subscriptionBlock();
    assert.match(
        block,
        /\.on\(\s*'AGENT_RELEASED'/,
        "改造方案 §14.1：服务端在坐席完成最后人工节点后只投递一次 AGENT_RELEASED，" +
        '坐席必须据此收尾（封存录像、挂断、退出交易页），否则会一直占着交易页',
    );
});

test('AGENT_RELEASED 收尾不等于 /close：流程还要继续走到办结', () => {
    const at = source.indexOf('async function releaseAfterAgentWork');
    assert.ok(at >= 0, '应有 releaseAfterAgentWork 函数');
    const body = source.slice(at, at + 1200);
    assert.doesNotMatch(
        body,
        /\/close/,
        'AGENT_RELEASED 后不能调 /close：后续正式转账、回执与结果通知仍由流程继续走完' +
        '（改造方案 §5.3 第 5 条），关掉实例会让业务半途终止',
    );
    assert.match(body, /release\(\)/, 'AGENT_RELEASED 收尾应复用 release()（本地释放绑定）');
});

test('AGENT_RELEASED 去重标记必须随新业务复位', () => {
    assert.match(
        source,
        /releasedProcessId\s*=\s*''/,
        'releasedProcessId 需在 bindBusiness（新业务）与收尾失败时复位，' +
        '否则同一坐席承接的下一单会被误判为"已释放"而不收尾',
    );
    const bind = source.indexOf('async function bindBusiness');
    const bindBody = source.slice(bind, bind + 600);
    assert.match(bindBody, /releasedProcessId\s*=\s*''/, 'bindBusiness 必须清掉上一单的释放标记');
});
