/*
 * 信息回执行（displayFields 只读展示）——通用兜底页与办结回执共用的渲染片段。
 * 原先挂在 ui/GenericNode.js（315 行场景渲染器）上，app/node-dialog.js 只为
 * 这一个纯函数依赖整个渲染器；抽出后两端都依赖薄模块，耦合对齐到功能本身。
 * 影像地址走 /records/ 白名单，其余一律转义（契约 §3.7）。
 *
 * 版式规范（「栏位说明 + 只读框」）：每个要素一行，左侧栏位说明、右侧带边框的
 * 只读值框，明确表达"这是回显、不可修改"。此前输出的是无 `vb-` 前缀的
 * `mobile-receipt-row`，与样式表里的 `.vb-mobile-receipt-row` 对不上，
 * 于是这些要素完全没有样式、标签与值挤在一起（签字页看起来像纯文本堆叠）。
 */
import { escape, money, safeRecordUrl, isRecordPdfUrl } from '../core/format.js';

/** 单行的值内容：图片 / PDF / 金额 / 纯文本各有取值口径。 */
function displayFor(field, value) {
    if (field.type === 'image') {
        const src = safeRecordUrl(value);
        return src
            ? `<img class="vb-node-receipt-image" src="${escape(src)}" alt="${escape(field.label)}" loading="lazy">`
            : '<span class="vb-field-empty">未上传</span>';
    }
    if (field.type === 'document' && isRecordPdfUrl(value)) {
        return `<a class="vb-document-link" href="${escape(value)}" target="_blank" rel="noopener">${escape(field.linkLabel || '查看PDF单据')}</a>`;
    }
    if (field.type === 'money' || field.type === 'amount') return escape(money(value));
    return escape(value ?? '—');
}

export function receiptRows(fields, values) {
    return (fields || []).map((field) => {
        const display = displayFor(field, values[field.name]);
        // 标签 + 只读值框：框体承载回显内容，视觉上明确不可编辑。
        return `<div class="vb-mobile-receipt-row"><span class="vb-field-label">${escape(field.label)}</span>`
            + `<div class="vb-field-readonly">${display}</div></div>`;
    }).join('');
}

export default receiptRows;
