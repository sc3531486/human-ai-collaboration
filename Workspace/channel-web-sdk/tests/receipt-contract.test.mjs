/*
 * 只读要素渲染契约：输出的 class 必须与样式表里的选择器一致。
 *
 * 背景（真实故障）：ui/receipt.js 输出的是无 `vb-` 前缀的 `mobile-receipt-row` 与
 * `node-receipt-image`，而样式表里只有 `.vb-mobile-receipt-row`（`node-receipt-image`
 * 根本没有）。类名对不上不会报错，只是"完全没有样式"——签字页的金额/收款人/账号/用途
 * 四行挤成纯文本堆叠，看起来像没有版式。这类静默失配必须由测试钉住。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const RECEIPT = fileURLToPath(new URL('../src/ui/receipt.js', import.meta.url));
const SDK_CSS = fileURLToPath(new URL('../../channel-web-sdk/src/styles/vb-banking.css', import.meta.url));
const HOLOGRAM_CSS = fileURLToPath(new URL('../../channel-web-sdk/src/styles/hologram.css', import.meta.url));

const receiptSource = readFileSync(RECEIPT, 'utf8');
const css = readFileSync(SDK_CSS, 'utf8') + '\n' + readFileSync(HOLOGRAM_CSS, 'utf8');

/** receipt.js 里出现的全部 class="..." 取值。 */
function emittedClasses() {
    const names = new Set();
    for (const m of receiptSource.matchAll(/class="([^"]+)"/g)) {
        for (const cls of m[1].split(/\s+/)) if (cls) names.add(cls);
    }
    return [...names];
}

test('receipt.js 输出的每个 class 都必须在样式表里有对应选择器', () => {
    const classes = emittedClasses();
    assert.ok(classes.length > 0, 'receipt.js 应输出至少一个 class');

    const missing = classes.filter(cls => !css.includes(`.${cls}`));
    assert.deepEqual(
        missing, [],
        `以下 class 没有任何样式选择器（版式会静默丢失）：${missing.join('、')}。` +
        '真实事故：mobile-receipt-row 漏了 vb- 前缀，与 .vb-mobile-receipt-row 对不上，' +
        '签字页的四个要素完全无样式。',
    );
});

test('只读要素的类名统一带 vb- 前缀（SDK 隔离约定）', () => {
    // vb- 前缀是"不污染宿主页面"的实现方式：所有选择器都收在 .vb-root 下。
    const unprefixed = emittedClasses().filter(cls => !cls.startsWith('vb-'));
    assert.deepEqual(
        unprefixed, [],
        `SDK 输出的 class 必须带 vb- 前缀，否则既有撞宿主样式的风险、又会与生成的样式表失配：${unprefixed.join('、')}`,
    );
});

test('回显版式是「栏位说明 + 只读框」而非裸文本', () => {
    assert.match(receiptSource, /vb-field-label/, '应有栏位说明元素（vb-field-label）');
    assert.match(receiptSource, /vb-field-readonly/, '应有只读值框元素（vb-field-readonly）');
    // 只读框必须是 div 语义（用 disabled input 会让人误以为"本来可改、只是被禁用"）
    assert.match(
        receiptSource,
        /<div class="vb-field-readonly">/,
        '只读值框应是 div：它表达"这是回显"，不是可编辑控件',
    );
});

test('只读框在样式表里有可见边框与底色（否则仍是裸文本观感）', () => {
    const rule = css.match(/\.vb-field-readonly\s*\{[^}]*\}/);
    assert.ok(rule, '样式表里应有 .vb-field-readonly 规则');
    assert.match(rule[0], /border\s*:/, '只读框必须有边框，才能看出"这是一个框"');
    assert.match(rule[0], /background\s*:/, '只读框应有底色，与可编辑控件区分');
});

test('图片与文档字段的 class 同样有样式', () => {
    for (const cls of ['vb-node-receipt-image', 'vb-document-link']) {
        assert.ok(css.includes(`.${cls}`), `${cls} 应有对应样式选择器`);
    }
});
