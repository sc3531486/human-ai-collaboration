/*
 * SDK ↔ video-widget 冒烟联调（lead 指派：task-2 对话追加汇报）。
 *
 * 用真实控件产物 `../video-widget/dist/vb-video.esm.js`（不是源码）经本 SDK 适配层
 * （src/core/video-widget.js）走通 node 可测链路：
 *   挂载降级 → open → connect（fake 传输 + 注入流）→ 事件转发 → capture 诚实 null
 *   → record.idle/成功回执/0 字节回执 → hangup 停 track。
 *
 * 测试替身抽公共在 tests/helpers/widget-fixtures.mjs（与 recording 测试复用）。
 * 浏览器专属环节（真实 stage 渲染 / 用户手势 gUM / UMD widgetUrl 注入 /
 * 真帧抓拍 Blob / 真 MediaRecorder 非 0 封存 / getTracks 停检）见
 * README「WebView 真机验证步骤清单」。
 *
 * 运行：npm test（node --test 通配 tests 目录下的 .test.mjs）。
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { configure, ensure, video as adapter, teardown } from '../src/core/video-widget.js';
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

// ---------- 冒烟链（单一文件内串行，控件是模块级单例） ----------

test('SDK↔控件冒烟：挂载降级 → open → connect → capture → record.seal → hangup', async (t) => {
    const widget = await loadWidgetProduct();
    /** 测试注入的本地流引用：hangup 后断言其 track 全部停止。 */
    let localStreamRef = null;

    await t.test('控件产品面与适配层归一化', async () => {
        assert.equal(widget.version, '1.0.0');
        assert.equal(typeof widget.mount, 'function');
        assert.equal(typeof widget.video.open, 'function');
        configure({ widgetModule: widget, recordingUpload });
        const api = await ensure();
        assert.equal(api, widget.video, '适配层应把命令宿主归一到 mod.video');
        assert.equal(adapter.available, true);
    });

    await t.test('挂载：node 无 DOM 时 mount 异常必须上抛（接缝禁止降级为 null）', async () => {
        // fail-fast 兜底清算：容器非法由控件拒绝，异常如实穿出接缝；
        // 无视频继续办理的兜底发生在业务层 video-session.engage（§2.9.3），不在接缝里。
        await assert.rejects(
            () => adapter.mountInto({}, { toolbar: false }),
            /容器元素/,
        );
    });

    await t.test('open：字符串 layout 被适配层拦截（否则控件会把字符下标展开进布局）', async () => {
        const ok = await adapter.open({ mode: 'dock', layout: 'audio' });
        assert.equal(ok, true);
        const state = await adapter.getState();
        assert.equal(state.mounted, true);
        assert.equal(state.mode, 'dock');
        assert.equal(typeof state.layout, 'object');
        assert.ok(!('0' in state.layout), "字符串 layout 'audio' 会摊出 {0:'a',…}——适配层必须丢弃");
        assert.equal(typeof state.layout.heightRatio, 'number');
    });

    await t.test('connect：参数翻译 + 事件转发 + 状态判据', async () => {
        widget.registerTransport('smoke', SmokeTransport);
        widget.setDefaultTransport('smoke');
        widget.video.useTransport('smoke', {});   // 传输由测试注入，不建网络链路

        const events = [];
        const off = adapter.on('state', (payload) => events.push(payload));

        localStreamRef = makeStream(['video', 'audio']);
        const ok = await adapter.connect({
            agentPeerId: 'agent-9527',
            processId: 'P-smoke',
            localStream: localStreamRef,
            desktop: false,
        });
        assert.equal(ok, true);

        const state = await adapter.getState();
        assert.equal(state.peerId, 'agent-9527');
        assert.equal(state.processId, 'P-smoke');
        assert.equal(state.hasLocal, true, '注入流应登记为本地流');
        assert.equal(state.connection, 'connected');
        assert.equal(state.hasRemote, true, 'subscribe 应派发远端流');
        assert.equal(state.call, true);
        assert.equal(state.hasDesktop, false, 'desktop:false 不得触发屏幕采集');

        // 事件转发：adapter.on('state') 收到的是控件状态快照
        assert.ok(events.length > 0, '控件状态事件应转发到适配层订阅者');
        assert.ok(events.some((e) => e.hasLocal === true), '应捕获到含 hasLocal 的快照');
        off();

        // 连通判据走接缝语义接口（换厂商时上层零改动）
        assert.equal(await adapter.isConnected(), true);
    });

    await t.test('takeover：只转发 av/audio/released 三值', async () => {
        const ok = await adapter.takeover('audio');
        assert.ok(ok);
        assert.equal((await adapter.getState()).takeover, 'audio');
        assert.equal(await adapter.takeover('digital-human'), null, '非法值不得转发给控件');
    });

    await t.test('capture：node 无画面时如实返回 null（不伪造 Blob）', async () => {
        assert.equal(await adapter.capture('local'), null);
        assert.equal(await adapter.capture('remote'), null);
    });

    // ---- 录制链：注入 indexedDB / fetch / MediaRecorder 全局替身 ----
    const recordGlobals = installRecordGlobals({ success: true, fileSize: 4096 });

    await t.test('record.idle seal：无录制时回执如实为「当前没有进行中的录制」', async () => {
        const receipt = await adapter.record.seal();
        assert.ok(receipt && typeof receipt === 'object');
        assert.equal(receipt.success, false);
        assert.equal(receipt.fileSize, 0);
        assert.equal(receipt.message, '当前没有进行中的录制');
    });

    let startedId = null;

    await t.test('record.start + seal：非 0 回执成立', async () => {
        recordGlobals.receipt = { success: true, fileSize: 4096 };
        const started = await adapter.record.start({
            processId: 'P-smoke',
            bizNo: 'B-smoke',
            nodeName: '账户余额查询',
            segmentIndex: 1,
            handlerType: 'DIGITAL_HUMAN',
        });
        assert.ok(started && started.recordingId, 'record.start 应返回 recordingId');
        startedId = started.recordingId;

        const receipt = await adapter.record.seal();
        assert.equal(receipt.success, true);
        assert.equal(receipt.fileSize, 4096);
        assert.equal(receipt.recordingId, startedId);
    });

    await t.test('record.seal：fileSize=0 时 success 必须落 false 且给出人话提示（红线）', async () => {
        recordGlobals.receipt = { success: true, fileSize: 0 };
        const started = await adapter.record.start({
            processId: 'P-smoke',
            bizNo: 'B-smoke',
            nodeName: '账户余额查询',
            segmentIndex: 1,
            handlerType: 'DIGITAL_HUMAN',
        });
        assert.ok(started && started.recordingId);

        const receipt = await adapter.record.seal();
        assert.equal(receipt.fileSize, 0);
        assert.equal(receipt.success, false, '0 字节绝不得宣告存证成功');
        assert.equal(receipt.message, '该段未采集到画面');
    });

    await t.test('record.seal：封存后再 seal 走「没有进行中的录制」而非旧回执', async () => {
        const receipt = await adapter.record.seal();
        assert.equal(receipt.success, false);
        assert.equal(receipt.message, '当前没有进行中的录制');
    });

    await t.test('hangup：停全部 track + 状态收尾（§8.4-5）', async () => {
        assert.equal(localStreamRef.getTracks().every((track) => track.readyState === 'live'), true);

        await adapter.hangup();

        const state = await adapter.getState();
        assert.equal(state.hasLocal, false, 'hangup 应释放本地流');
        assert.equal(state.hasRemote, false);
        assert.equal(state.call, false);
        assert.equal(state.connection, 'ended');
        assert.equal(await adapter.isConnected(), false, '挂断后连通判据必须回落 false');
        assert.equal(
            localStreamRef.getTracks().every((track) => track.readyState === 'ended'),
            true,
            '注入流的 track 也必须被停掉（红线：不得留僵尸轨道）',
        );
    });

    await t.test('teardown：订阅清理 + 未接入时安全降级', async () => {
        teardown();
        assert.equal(adapter.available, false);
        assert.equal(await ensure(), null);
        configure({ widgetUrl: 'https://example.invalid/vb-video.umd.js' });
        assert.equal(await ensure(), null, 'node 无 document：widgetUrl 注入应降级为 null 而非抛错');
        assert.equal(await adapter.capture('local'), null);
        assert.equal(await adapter.isConnected(), false, '未接入时连通判据为 false');
        assert.equal(await adapter.record.seal(), null, '未接入时 seal 回执为 null，业务不得据此宣告成功');
    });
});
