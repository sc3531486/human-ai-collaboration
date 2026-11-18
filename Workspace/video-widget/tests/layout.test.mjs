/**
 * 布局解析单测：比例 / 像素 / clamp 边界 / dock 退化 / 落位归一化。
 * 覆盖契约 §2.9.2 的每一条判据。
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import {
    resolveLayout, resolveDock, resolve, clamp, normalize, ratio,
    persistLayout, restoreLayout, mergeLayout, DEFAULT_LAYOUT, DEFAULT_DOCK_LAYOUT
} from '../src/layout.js';
import { MemoryStorage } from './helpers.js';

const VIEW = { width: 1000, height: 800 };

describe('ratio：归一化值钳制', () => {
    test('越界比例被钳到 0~1', () => {
        assert.equal(ratio(-0.2), 0);
        assert.equal(ratio(1.5), 1);
        assert.equal(ratio(0.42), 0.42);
    });

    test('非数字按 0 处理，不产生 NaN 布局', () => {
        assert.equal(ratio('abc'), 0);
        assert.equal(ratio(undefined), 0);
    });
});

describe('resolveLayout：比例为主', () => {
    test('契约示例：overlay 右上角客户窗', () => {
        const box = resolveLayout({ left: 0.72, top: 0.05, width: 0.26, height: 0.30 }, VIEW);
        assert.deepEqual(box, { left: 720, top: 40, width: 260, height: 240 });
    });

    test('默认布局即可用，不需要调用方每次都给全参数', () => {
        const box = resolveLayout(DEFAULT_LAYOUT, VIEW);
        assert.equal(box.width, 260);
        assert.equal(box.left, 720);
    });

    test('right / bottom 是 left / top 的反向写法', () => {
        const box = resolveLayout({ right: 0.02, bottom: 0.02, width: 0.2, height: 0.2 }, VIEW);
        assert.equal(box.width, 200);
        assert.equal(box.height, 160);
        assert.equal(box.left, 1000 - 200 - 20);
        assert.equal(box.top, 800 - 160 - 16);
    });

    test('空配置退化为左上角零尺寸，而不是抛错', () => {
        const box = resolveLayout({}, VIEW);
        assert.deepEqual(box, { left: 0, top: 0, width: 0, height: 0 });
    });
});

describe('resolveLayout：像素覆盖', () => {
    test('pixel 存在时覆盖对应维度的比例换算结果', () => {
        const box = resolveLayout({ left: 0.72, top: 0.05 }, VIEW, { width: 320, height: 180 });
        assert.equal(box.width, 320);
        assert.equal(box.height, 180);
        // ★ 原期望值 left === 720 已被更正为 680：pixel 放大宽度后，0.72 的原点会把窗口
        //   右边缘推到 720+320=1040 > 容器 1000。边界 clamp 是红线（任何配置都不允许推出容器），
        //   因此正确行为是钳到 1000-320=680。原期望值违反边界约束，是测试写错了而非实现写错了。
        assert.equal(box.left, 680);
        assert.equal(box.top, 40);
    });

    test('★ 像素覆盖同样受 clamp 约束，不得借精确对齐把窗口推出容器', () => {
        const box = resolveLayout({ left: 0.9 }, VIEW, { width: 400, height: 200 });
        assert.equal(box.left + box.width, 1000, '右边缘必须贴合容器，不得溢出');
        assert.equal(box.top + box.height, 200);
    });

    test('pixel 只覆盖 width 时 height 仍按比例', () => {
        const box = resolveLayout({ width: 0.26, height: 0.30 }, VIEW, { width: 320 });
        assert.equal(box.width, 320);
        assert.equal(box.height, 240);
    });

    test('pixel 的 left/top 也参与换算', () => {
        const box = resolveLayout({ left: 0.72, top: 0.05, width: 0.26, height: 0.30 }, VIEW, { left: 500, top: 100 });
        assert.equal(box.left, 500);
        assert.equal(box.top, 100);
    });
});

describe('clamp：边界钳制（任何配置都不允许推出容器）', () => {
    test('left/top 为负时钳到 0', () => {
        const box = clamp({ left: -50, top: -30, width: 200, height: 100 }, VIEW);
        assert.equal(box.left, 0);
        assert.equal(box.top, 0);
    });

    test('越界时钳到「容器尺寸 - 自身尺寸」而不是容器边缘', () => {
        const box = clamp({ left: 9999, top: 9999, width: 200, height: 100 }, VIEW);
        assert.equal(box.left, 800);
        assert.equal(box.top, 700);
        assert.equal(box.left + box.width, 1000);
        assert.equal(box.top + box.height, 800);
    });

    test('尺寸大于容器时截断到容器尺寸并保持左上对齐', () => {
        const box = clamp({ left: 10, top: 10, width: 5000, height: 5000 }, VIEW);
        assert.deepEqual(box, { left: 0, top: 0, width: 1000, height: 800 });
    });

    test('组合越界：比例算出 0.9 + 0.26 的溢出也要被拉回', () => {
        const box = resolveLayout({ left: 0.9, top: 0.9, width: 0.26, height: 0.30 }, VIEW);
        assert.equal(box.left + box.width, 1000);
        assert.equal(box.top + box.height, 800);
    });

    test('容器尺寸为 0 时不产生负数或 NaN', () => {
        const box = clamp({ left: 10, top: 10, width: 100, height: 100 }, { width: 0, height: 0 });
        assert.deepEqual(box, { left: 0, top: 0, width: 0, height: 0 });
    });

    test('负值尺寸按 0 处理', () => {
        const box = clamp({ left: 0, top: 0, width: -100, height: -100 }, VIEW);
        assert.equal(box.width, 0);
        assert.equal(box.height, 0);
    });
});

describe('resolveDock：移动端流式布局退化', () => {
    test('只剩 order 与 heightRatio', () => {
        const dock = resolveDock({ order: 2, heightRatio: 0.35 }, { height: 800 });
        assert.deepEqual(dock, { order: 2, heightRatio: 0.35, height: 280 });
    });

    test('缺省时用默认顺序与高度占比', () => {
        const dock = resolveDock({}, { height: 800 });
        assert.equal(dock.order, DEFAULT_DOCK_LAYOUT.order);
        assert.equal(dock.heightRatio, DEFAULT_DOCK_LAYOUT.heightRatio);
    });

    test('heightRatio 越界被钳制', () => {
        assert.equal(resolveDock({ heightRatio: 3 }, { height: 800 }).heightRatio, 1);
        assert.equal(resolveDock({ heightRatio: -1 }, { height: 800 }).heightRatio, 0);
    });
});

describe('resolve：按模式分派', () => {
    test('dock 走退化布局', () => {
        assert.equal(resolve('dock', { order: 1 }, { height: 800 }).order, 1);
    });

    test('overlay 走像素换算', () => {
        assert.equal(resolve('overlay', { left: 0.5 }, VIEW).left, 500);
    });
});

describe('normalize：拖拽落位回写归一化值', () => {
    test('回写的是比例而不是像素', () => {
        const normalized = normalize({ left: 500, top: 200, width: 300, height: 240 }, VIEW);
        assert.deepEqual(normalized, { left: 0.5, top: 0.25, width: 0.3, height: 0.3 });
    });

    test('换分辨率后换算回来仍在相对位置', () => {
        const normalized = normalize({ left: 500, top: 200, width: 300, height: 240 }, VIEW);
        const again = resolveLayout(normalized, { width: 2000, height: 1600 });
        assert.equal(again.left, 1000);
        assert.equal(again.width, 600);
    });

    test('容器尺寸为 0 时不产生 NaN', () => {
        const normalized = normalize({ left: 10, top: 10, width: 10, height: 10 }, { width: 0, height: 0 });
        assert.deepEqual(normalized, { left: 0, top: 0, width: 0, height: 0 });
    });

    test('拖拽越界后回写的值也落在 0~1 内', () => {
        const normalized = normalize({ left: -80, top: 900, width: 300, height: 240 }, VIEW);
        assert.ok(normalized.left >= 0 && normalized.left <= 1);
        assert.ok(normalized.top >= 0 && normalized.top <= 1);
    });
});

describe('持久化', () => {
    test('落位后能按同一 key 读回', () => {
        const storage = new MemoryStorage();
        persistLayout({ left: 0.5, top: 0.25 }, { storage });
        assert.deepEqual(restoreLayout({ left: 0.72 }, { storage }), { left: 0.5, top: 0.25 });
    });

    test('无记录时返回 fallback 的副本', () => {
        const storage = new MemoryStorage();
        const fallback = { left: 0.72 };
        const restored = restoreLayout(fallback, { storage });
        assert.deepEqual(restored, fallback);
        assert.notEqual(restored, fallback);
    });

    test('存储不可用时退化为不持久化，不抛错', () => {
        const throwing = { setItem() { throw new Error('quota'); }, getItem() { throw new Error('denied'); } };
        assert.doesNotThrow(() => persistLayout({ left: 0.5 }, { storage: throwing }));
        assert.deepEqual(restoreLayout({ left: 0.72 }, { storage: throwing }), { left: 0.72 });
    });
});

describe('mergeLayout', () => {
    test('后写的覆盖先写的，null/undefined 不覆盖', () => {
        assert.deepEqual(mergeLayout({ left: 0.1, top: 0.2 }, { left: 0.9, top: null }), { left: 0.9, top: 0.2 });
    });
});
