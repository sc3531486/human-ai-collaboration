/*
 * 节点/等待判据（core/flow/policy.js）——§3.6 红线判据的单一事实源回归。
 * 该判据原先散落在 wait-tone.sync 与 instance.applyState 两处且口径不一，
 * 抽公共后以本测试锁定语义：改判据必须先改这里，两处消费端自动同源。
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { isHumanVideoNode, isWaitingForAgent } from '../src/core/flow/policy.js';

test('isHumanVideoNode：人工视频/见证节点识别', async (t) => {
    await t.test('nodePolicy.video 命中', () => {
        assert.equal(isHumanVideoNode({ nodePolicy: { video: true } }), true);
    });

    await t.test('communication=VIDEO / view=witness 命中（Flowable 与页面视图同权）', () => {
        assert.equal(isHumanVideoNode({ nodePolicy: { communication: 'VIDEO' } }), true);
        assert.equal(isHumanVideoNode({ nodePolicy: { view: 'witness' } }), true);
    });

    await t.test('policy 缺字段时按节点名中文兜底（防配置漏判）', () => {
        assert.equal(isHumanVideoNode({ nodeName: '人工授权业务办理' }), true);
        assert.equal(isHumanVideoNode({ nodeName: '座席视频核实' }), true);   // 座=U+5EA7，与基线「座席视频图层」同字
        assert.equal(isHumanVideoNode({ nodeName: '账户余额查询', nodePolicy: {} }), false);
    });

    await t.test('普通表单节点不命中', () => {
        assert.equal(isHumanVideoNode({ nodeName: '转账信息录入', nodePolicy: { action: 'SUBMIT' } }), false);
        assert.equal(isHumanVideoNode(null), false);
    });
});

test('isWaitingForAgent：等待音乐判据（RUNNING && 人工视频 && 未释放 && 未接入 && 未连通）', async (t) => {
    const base = {
        processStatus: 'RUNNING',
        nodeName: '人工授权业务办理',
        nodePolicy: { video: true },
        vars: {},
    };

    await t.test('全条件满足 → 等待', () => {
        assert.equal(isWaitingForAgent(base, false), true);
    });

    await t.test('已接入（connected）→ 不等待', () => {
        assert.equal(isWaitingForAgent(base, true), false);
    });

    await t.test('快照已有坐席，但入房通知未到时仍然等待', () => {
        assert.equal(isWaitingForAgent({ ...base, vars: { agent_session_id: 'S-1' } }, false), true);
        assert.equal(isWaitingForAgent({ ...base, agentPeerId: 'agent-1' }, false), true);
    });

    await t.test('新人工节点不受上一段 released 状态影响', () => {
        assert.equal(isWaitingForAgent({ ...base, takeover_mode: 'released' }, false), true);
        assert.equal(isWaitingForAgent({ ...base, takeover_mode: 'released' }, true), false);
    });

    await t.test('非 RUNNING 或非人工视频节点 → 不等待', () => {
        assert.equal(isWaitingForAgent({ ...base, processStatus: 'COMPLETED' }, false), false);
        assert.equal(isWaitingForAgent({ ...base, nodeName: '转账信息录入', nodePolicy: { action: 'SUBMIT' } }, false), false);
    });
});
