/*
 * 宿主生命周期回归：mount() 之后必须调 startBusiness()。
 *
 * 背景（真实故障）：宿主只调 mount、不调 startBusiness 时，
 *   - 页面上第一个节点（数字人「信息采集」）的表单照常出现 —— 那是数字人对话
 *     /api/balance/ai/chat 的响应直接交给 nodeDialog 渲染的，不经过实时通道；
 *   - 但 core/flow/sync.js 的 onSnapshot 仍是初始的 no-op，服务端后续推送的
 *     节点状态（典型：坐席授权后推进到 customer_sign）被静默丢弃。
 * 现象就是「坐席已授权、流程已到客户签名，客户侧却不弹签名框」。
 *
 * 本测试不看真实 SDK 内部实现，只钉住宿主调用契约：从 BankView.vue 源码里
 * 断言 mount 之后确实存在 startBusiness 调用，且不能被静默忽略。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const BANK_VIEW = fileURLToPath(new URL('../src/views/BankView.vue', import.meta.url));

const source = readFileSync(BANK_VIEW, 'utf8');

test('宿主挂载 SDK 后必须调用 startBusiness（否则节点推送全部丢失）', () => {
    assert.match(
        source,
        /await\s+instance\.startBusiness\(\)/,
        'BankView.vue 必须在 mount 之后 await instance.startBusiness()；' +
        '缺少它时首节点表单仍会显示，但服务端推送的后续节点（如客户签名）不会弹框。',
    );
});

test('startBusiness 调用必须在 mount 之后，且受方法存在性保护（桩件 SDK 无此方法）', () => {
    const mountAt = source.indexOf('sdk.api.mount(');
    const startAt = source.search(/await\s+instance\.startBusiness\(\)/);

    assert.ok(mountAt >= 0, '应能在宿主里找到 sdk.api.mount( 调用');
    assert.ok(startAt > mountAt, 'startBusiness() 必须排在 mount() 之后');

    // 桩件 SDK（dist 产物缺失时的降级替身，见 core/mock-sdk.js）没有 startBusiness，
    // 必须做能力判断，否则降级路径会直接抛错、整页变成错误态。
    const guard = source.slice(Math.max(0, startAt - 400), startAt);
    assert.match(
        guard,
        /typeof\s+instance\.startBusiness\s*===\s*'function'/,
        'startBusiness 调用前必须判断方法存在，兼容无该方法的桩件 SDK',
    );
});

test('桩件 SDK 确实没有 startBusiness —— 保护分支不是死代码', async () => {
    const { loadMockSdk } = await import('../src/core/mock-sdk.js');
    const api = loadMockSdk();
    assert.equal(
        typeof api.startBusiness,
        'undefined',
        '桩件 SDK 未实现 startBusiness；若将来补上，应同步调整宿主的兼容分支',
    );
});
