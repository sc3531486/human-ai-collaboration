/**
 * 抓帧与合成单测：drawCover 等比裁切（不硬拉伸）、capture 返回 Blob 语义。
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { drawCover, capture, TripleScreenCompositor, COMPOSITE_WIDTH, COMPOSITE_HEIGHT } from '../src/media/capture.js';
import { makeCanvas, makeVideoSource, FakeBlob } from './helpers.js';

describe('drawCover：object-fit cover 等比裁切', () => {
    test('源比目标宽时按高度放大、横向居中裁切', () => {
        const canvas = makeCanvas();
        const context = canvas.getContext('2d');
        // 源 2000×1000（2:1），目标 320×270（约 1.185:1）
        drawCover(context, makeVideoSource(2000, 1000), 0, 0, 320, 270);
        const call = canvas.calls.at(-1);
        assert.equal(call.op, 'drawImage');
        const [, sx, sy, sw, sh, dx, dy, dw, dh] = call.args;
        assert.equal(dw, 320);
        assert.equal(dh, 270);
        // scale = max(320/2000, 270/1000) = 0.27 → crop = 320/0.27, 270/0.27
        const scale = Math.max(320 / 2000, 270 / 1000);
        assert.ok(Math.abs(sw - 320 / scale) < 1e-6, '裁切宽必须等于目标宽 / scale');
        assert.ok(Math.abs(sh - 270 / scale) < 1e-6);
        // 居中：左边距 = (源宽 - 裁切宽) / 2
        assert.ok(Math.abs(sx - (2000 - sw) / 2) < 1e-6);
        assert.ok(Math.abs(sy - (1000 - sh) / 2) < 1e-6);
        assert.equal(dx, 0);
        assert.equal(dy, 0);
    });

    test('源比目标高（竖屏）时按宽度放大、纵向居中裁切，不硬拉伸', () => {
        const canvas = makeCanvas();
        const context = canvas.getContext('2d');
        // 手机竖屏 720×1280 塞进 320×270
        drawCover(context, makeVideoSource(720, 1280), 640, 270, 320, 270);
        const call = canvas.calls.at(-1);
        const [, sx, sy, sw, sh, dx, dy, dw, dh] = call.args;
        const scale = Math.max(320 / 720, 270 / 1280);
        assert.equal(scale, 320 / 720, '竖屏应取宽度方向的放大系数');
        assert.ok(Math.abs(sw - 320 / scale) < 1e-6);
        assert.ok(Math.abs(sh - 270 / scale) < 1e-6);
        assert.equal(dx, 640);
        assert.equal(dy, 270);
        assert.ok(sy > 0, '纵向确实被裁掉了一部分（居中）');
    });

    test('★ 宽高比守恒：裁切区域的宽高比等于目标的宽高比（即不拉伸）', () => {
        const canvas = makeCanvas();
        const context = canvas.getContext('2d');
        drawCover(context, makeVideoSource(1920, 1080), 0, 0, 640, 540);
        const [, , , sw, sh] = canvas.calls.at(-1).args;
        assert.ok(Math.abs((sw / sh) - (640 / 540)) < 1e-6, '裁切框比例必须等于目标框比例');
    });

    test('源还没有尺寸时不画，也不抛错', () => {
        const canvas = makeCanvas();
        const context = canvas.getContext('2d');
        assert.equal(drawCover(context, makeVideoSource(0, 0), 0, 0, 320, 270), false);
        assert.equal(drawCover(context, null, 0, 0, 320, 270), false);
        assert.equal(canvas.calls.filter(call => call.op === 'drawImage').length, 0);
    });
});

describe('capture：返回 Blob 语义', () => {
    test('有画面时返回 Blob', async () => {
        const source = makeVideoSource(1280, 720);
        const canvas = makeCanvas(1280, 720);
        const blob = await capture(source, {
            canvas,
            toBlob: cb => cb(new FakeBlob(['png-bytes'], { type: 'image/png' }))
        });
        assert.ok(blob, '应返回 Blob');
        assert.equal(blob.type, 'image/png');
        assert.ok(blob.size > 0);
    });

    test('★ 未收到画面时返回 null，绝不伪造占位图', async () => {
        assert.equal(await capture(makeVideoSource(0, 0), { canvas: makeCanvas() }), null);
        assert.equal(await capture(null, { canvas: makeCanvas() }), null);
        // readyState < 2：还没有可绘制的帧
        assert.equal(await capture(makeVideoSource(1280, 720, 1), { canvas: makeCanvas() }), null);
    });

    test('★ toBlob 给出空 Blob 时如实返回 null，不拿空图冒充成功', async () => {
        const blob = await capture(makeVideoSource(640, 480), {
            canvas: makeCanvas(),
            toBlob: cb => cb(new FakeBlob([]))
        });
        assert.equal(blob, null);
    });

    test('toBlob 返回 null 时不抛错，如实返回 null', async () => {
        const blob = await capture(makeVideoSource(640, 480), {
            canvas: makeCanvas(),
            toBlob: cb => cb(null)
        });
        assert.equal(blob, null);
    });

    test('画布按源的真实分辨率建立，不缩放', async () => {
        const canvas = makeCanvas(10, 10);
        await capture(makeVideoSource(1280, 720), { canvas, toBlob: cb => cb(new FakeBlob(['x'])) });
        assert.equal(canvas.width, 1280);
        assert.equal(canvas.height, 720);
    });
});

describe('TripleScreenCompositor：三分屏', () => {
    test('默认浏览器定时器保留 Window 接收者，启动和停止不触发 Illegal invocation', () => {
        const originalSet = globalThis.setInterval;
        const originalClear = globalThis.clearInterval;
        const handles = new Set();
        let next = 0;
        try {
            globalThis.setInterval = function () {
                if (this !== globalThis) throw new TypeError('Illegal invocation');
                handles.add(++next);
                return next;
            };
            globalThis.clearInterval = function (handle) {
                if (this !== globalThis) throw new TypeError('Illegal invocation');
                handles.delete(handle);
            };
            const compositor = new TripleScreenCompositor({ canvas: makeCanvas() });
            compositor.start();
            compositor.start();
            assert.equal(handles.size, 1);
            compositor.stop();
            assert.equal(handles.size, 0);
        } finally {
            globalThis.setInterval = originalSet;
            globalThis.clearInterval = originalClear;
        }
    });
    test('三路画面按 640×540 + 320×270 ×2 布局', () => {
        const canvas = makeCanvas(COMPOSITE_WIDTH, COMPOSITE_HEIGHT);
        const compositor = new TripleScreenCompositor({ canvas });
        compositor.setSource('desktop', makeVideoSource(1920, 1080));
        compositor.setSource('remote', makeVideoSource(720, 1280));
        compositor.setSource('local', makeVideoSource(960, 540));
        compositor.drawFrame();

        const draws = canvas.calls.filter(call => call.op === 'drawImage');
        assert.equal(draws.length, 3, '三路画面同帧合成');
        const [, , , , , dx1, dy1, dw1, dh1] = draws[0].args;
        const [, , , , , dx2, dy2, dw2, dh2] = draws[1].args;
        const [, , , , , dx3, dy3] = draws[2].args;
        assert.deepEqual([dx1, dy1, dw1, dh1], [0, 0, 640, 540], '左侧坐席桌面');
        assert.deepEqual([dx2, dy2, dw2, dh2], [640, 0, 320, 270], '右上客户摄像头');
        assert.deepEqual([dx3, dy3], [640, 270], '右下坐席摄像头');
    });

    test('缺某一路时其余两路照常合成', () => {
        const canvas = makeCanvas();
        const compositor = new TripleScreenCompositor({ canvas });
        compositor.setSource('desktop', makeVideoSource(1920, 1080));
        compositor.drawFrame();
        assert.equal(canvas.calls.filter(call => call.op === 'drawImage').length, 1);
    });

    test('三路都缺时只画背景，不抛错', () => {
        const canvas = makeCanvas();
        const compositor = new TripleScreenCompositor({ canvas });
        assert.equal(compositor.drawFrame(), true);
        assert.equal(canvas.calls.filter(call => call.op === 'drawImage').length, 0);
    });

    test('标签文字与时间戳都绘出（存证画面可辨识）', () => {
        const canvas = makeCanvas();
        const compositor = new TripleScreenCompositor({ canvas, timestamp: () => '2026/1/1 10:00:00' });
        compositor.drawFrame();
        const texts = canvas.calls.filter(call => call.op === 'fillText').map(call => call.args[0]);
        assert.deepEqual(texts.slice(0, 3), ['坐席桌面', '客户摄像头', '坐席摄像头']);
        assert.match(texts.at(-1), /人工见证 · 2026\/1\/1 10:00:00/);
    });

    test('start/stop 不会叠加 timer', () => {
        let count = 0;
        const handles = new Set();
        const compositor = new TripleScreenCompositor({
            canvas: makeCanvas(),
            setInterval: () => { const handle = ++count; handles.add(handle); return handle; },
            clearInterval: handle => handles.delete(handle)
        });
        compositor.start();
        compositor.start();
        assert.equal(handles.size, 1, '重复 start 只保留一个循环');
        compositor.stop();
        assert.equal(handles.size, 0);
    });
});
