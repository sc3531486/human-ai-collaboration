/*
 * fail-fast 兜底清算回归（lead 裁定：异常不得经接缝降级为 null/console.warn 静默、
 * 不得静默成功；契约缺席语义按 §2.9.3 保留）：
 *   - 缺席（控件未配置/未装载）→ null/false，业务按「无视频办理到底」继续；
 *   - 异常（控件在场但命令被拒）→ 原样上抛，绝不吞成 null/静默 false；
 *   - 开段被拒 → 计入 missing 台账，办结 warnings() 如实告警（不静默跳过）。
 * 纯替身夹具、不依赖控件产物；node --test 每文件独立进程，可自由操纵全局装载态。
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { configure, ensure, video as adapter, teardown } from '../src/core/video-widget.js';
import { createRecording } from '../src/app/recording.js';

test('接缝严格两态：缺席为 null、命令拒绝上抛、开段失败不静默', async (t) => {
    await t.test('缺席：未配置控件时命令返回 null/false（不是异常）', async () => {
        teardown();
        configure({});
        assert.equal(await ensure(), null, '未配置 ⇒ 控件缺席');
        assert.equal(await adapter.capture('local'), null, '缺席抓帧如实 null');
        assert.equal(await adapter.record.seal(), null, '缺席封存回执 null，不得据此宣告成功');
        assert.equal(await adapter.open({ mode: 'dock' }), false, '缺席 open 返回 false 而非抛错');
    });

    await t.test('异常：控件在场但 open 被拒 → 原样上抛（不降级为 null/false）', async () => {
        const fake = {
            video: {
                open() { throw new Error('控件内部故障'); },
            },
        };
        configure({ widgetModule: fake });
        assert.ok(await ensure(), '伪控件应被装载（确保处于「在场」态）');
        await assert.rejects(() => adapter.open({ mode: 'dock' }), /控件内部故障/);
    });

    await t.test('开段被拒 → 计入 missing，warnings 如实告警（不静默跳过）', async () => {
        const fake = {
            video: {
                open() { return true; },
                getState() { return {}; },
                record: {
                    start() { throw new Error('开段被拒'); },
                },
            },
        };
        configure({ widgetModule: fake });
        assert.ok(await ensure(), '伪控件应被装载');

        const rec = createRecording({
            getProcessId: () => 'P-ff',
            getBizNo: () => 'B-ff',
            getNodeName: () => '人工授权业务办理',
            isDisposed: () => false,
        });
        assert.equal(await rec.start(1, 'DIGITAL_HUMAN'), false, '开段失败如实返回 false');
        assert.equal(rec.active, false);
        assert.deepEqual(rec.missing, [1], '开段被拒必须计入 missing 台账（禁止静默跳过）');
        const lines = rec.warnings();
        assert.equal(lines.length, 1, '办结告警必须覆盖被拒开段');
        assert.match(lines[0], /第 1 段未取得封存回执/);
    });
});
