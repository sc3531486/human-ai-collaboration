/*
 * 录像分段台账（src/app/recording.js）——抽公共后的红线语义回归：
 *   - 非 0 封存入台账 → warnings() 干净；
 *   - fileSize === 0 → 台账如实记 0，warnings() 告警（绝不宣告成功）；
 *   - 回执缺失与 0 字节分开陈述（不并入成功、不混同）；
 *   - 控件未接入 ⇒ 开段如实跳过。
 * 驱动真实控件产物（与 widget-smoke 同口径，复用 tests/helpers 夹具）。
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { configure, ensure, video as adapter, teardown } from '../src/core/video-widget.js';
import { createRecording } from '../src/app/recording.js';
import {
    makeStream,
    SmokeTransport,
    installRecordGlobals,
    loadWidgetProduct,
} from './helpers/widget-fixtures.mjs';

async function recordingUpload(form) {
    const response = await fetch('/api/balance/record/stream-chunk', { method: 'POST', body: form });
    return response.json();
}

test('录像分段台账：开段 → 封存 → 0 字节告警 → 未接入跳过', async (t) => {
    const widget = await loadWidgetProduct();
    configure({ widgetModule: widget, recordingUpload });
    widget.registerTransport('smoke', SmokeTransport);
    widget.setDefaultTransport('smoke');
    widget.video.useTransport('smoke', {});

    const recordGlobals = installRecordGlobals({ success: true, fileSize: 4096 });

    // 与冒烟同口径：先装载控件（命令面在 ensure 之前缺席返回 null），再 open 后建链——
    // 控件新语义（widget-dev 收尾）：未先 video.open() 就 connect 会明确抛错
    // 「控件尚未初始化：请先调用 video.open({...}) 再 connect()」；
    // 生产链路同序（video-session.engage：mountInto → open → connect），测试对齐。
    assert.ok(await ensure(), '适配层应装载到控件命令面');
    assert.equal(await adapter.open({ mode: 'dock' }), true, 'connect 前必须先 open（控件新前置）');
    const connected = await adapter.connect({
        agentPeerId: 'agent-9527',
        processId: 'P-rec',
        localStream: makeStream(['video', 'audio']),
        desktop: false,
    });
    assert.equal(connected, true);

    const rec = createRecording({
        getProcessId: () => 'P-rec',
        getBizNo: () => 'B-rec',
        getNodeName: () => '人工授权业务办理',
        isDisposed: () => false,
    });

    await t.test('开段成功且进行中防重入', async () => {
        assert.equal(await rec.start(1, 'DIGITAL_HUMAN'), true);
        assert.equal(rec.active, true);
        assert.equal(rec.index, 1);
        assert.equal(await rec.start(2, 'DIGITAL_HUMAN'), false, '录制进行中不得重复开段');
    });

    await t.test('非 0 封存入台账，warnings 干净', async () => {
        recordGlobals.receipt = { success: true, fileSize: 4096 };
        const sealed = await rec.finish(1);
        assert.equal(sealed && sealed.success, true);
        assert.equal(rec.active, false);
        assert.deepEqual(rec.results, [{ segmentIndex: 1, fileSize: 4096 }]);
        assert.deepEqual(rec.warnings(), [], '台账干净时办结不产生告警');
    });

    await t.test('0 字节封存：台账如实记 0，warnings 告警（红线）', async () => {
        recordGlobals.receipt = { success: true, fileSize: 0 };
        assert.equal(await rec.start(2, 'DIGITAL_HUMAN'), true);
        const sealed = await rec.finish(2);
        assert.equal(sealed.fileSize, 0);
        assert.equal(sealed.success, false, '0 字节绝不得宣告存证成功');
        assert.deepEqual(rec.results, [
            { segmentIndex: 1, fileSize: 4096 },
            { segmentIndex: 2, fileSize: 0 },
        ]);
        const lines = rec.warnings();
        assert.equal(lines.length, 1);
        assert.match(lines[0], /第 2 段录像未采集到画面/);
        assert.ok(!lines[0].includes('未取得封存回执'), '0 字节与回执缺失必须分开陈述');
    });

    await t.test('未开段 finish → null，不产生台账', async () => {
        assert.equal(await rec.finish(9), null);
        assert.equal(rec.results.length, 2, '未开段的 finish 不得污染台账');
        assert.equal(rec.missing.length, 0);
    });

    await t.test('控件未接入（teardown）⇒ 开段如实跳过', async () => {
        teardown();
        const fresh = createRecording({
            getProcessId: () => 'P-rec',
            getBizNo: () => 'B-rec',
            getNodeName: () => '人工授权业务办理',
            isDisposed: () => false,
        });
        assert.equal(await fresh.start(1, 'DIGITAL_HUMAN'), false, '无控件 ⇒ 无画面可采，如实跳过');
        assert.equal(fresh.active, false);
        assert.deepEqual(fresh.warnings(), []);
    });
});
