/*
 * 通用兜底节点页（契约 §2.1 第 4 步）：纯由后端 nodePolicy 驱动，无任何场景专属假设。
 * 三种版式按字段类型分派，逐条移植自 navis-web：
 *   - signature 字段   → mobile-signature.js（签字 + 确认勾选 + 先存证后提交）
 *   - card / photo 字段 → vb-mobile-node-forms.js（选卡 / 拍照 / 人脸，版本号防串写）
 *   - 其余             → mobile_balance.html showMobileForm 的通用表单
 * 老 SDK 办新业务（契约 §6.3-1）：后端新增或修改 nodePolicy，本文件无需改动即可渲染推进。
 */
import { escape } from '../core/format.js';
import { receiptRows } from './receipt.js';

const esc = escape;

/** 表单公共外壳：主体只保留当前节点需要填写或核对的业务要素。 */
function formShell(state, policy, bodyHtml, noteHtml) {
    return `<form class="vb-mobile-node-form vb-password-form"><div class="vb-node-form-body">${bodyHtml}</div><footer class="vb-node-form-footer"><p class="vb-node-form-note">${noteHtml}</p><button class="vb-node-primary" type="submit">${esc(policy.actionLabel || '确认')}</button><p class="vb-node-form-error" role="alert"></p></footer></form>`;
}

/* ------------------------------------------------------------------ 签字版式 */

function renderSignature(panel, state, ctx, field) {
    const policy = state.nodePolicy;
    const fields = policy.displayFields || [];
    const values = ctx.values;
    const confirmation = (policy.fields || []).find(item => item.type === 'checkbox');
    if (!confirmation) throw new Error('签字节点未声明确认字段');
    panel.innerHTML = `<form class="vb-mobile-node-form vb-signature-form"><div class="vb-node-form-body">${receiptRows(fields, values)}<div class="vb-signature-heading"><label class="vb-signature-label">客户签字</label><button type="button" class="vb-signature-reset" data-clear>清除重签</button></div><div class="vb-signature-pad"><canvas width="800" height="400" aria-label="手写签字区域"></canvas><span class="vb-signature-placeholder">请在此处手写签字</span></div><p class="vb-signature-hint">请使用手指签写本人姓名。</p><label class="vb-node-checkbox"><input type="checkbox" name="${esc(confirmation.name)}" required><span>${esc(confirmation.label)}</span></label></div><footer class="vb-node-form-footer"><button type="submit" class="vb-node-primary">${esc(policy.actionLabel || '确认提交')}</button><p class="vb-node-form-error" role="alert"></p></footer></form>`;

    const form = panel.querySelector('form');
    const canvas = form.querySelector('canvas');
    const context = canvas.getContext('2d');
    const button = form.querySelector('[type="submit"]');
    const error = form.querySelector('.vb-node-form-error');
    const placeholder = form.querySelector('.vb-signature-placeholder');
    let points = 0;
    let drawing = false;
    let busy = false;

    function clearPad() {
        context.fillStyle = '#fff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.strokeStyle = '#142e4d';
        context.lineWidth = 4;
        context.lineCap = 'round';
        points = 0;
        if (placeholder) placeholder.hidden = false;
    }

    function position(event) {
        const rect = canvas.getBoundingClientRect();
        return [(event.clientX - rect.left) * canvas.width / rect.width, (event.clientY - rect.top) * canvas.height / rect.height];
    }

    clearPad();
    canvas.onpointerdown = (event) => {
        if (busy) return;
        event.preventDefault();
        canvas.setPointerCapture(event.pointerId);
        if (placeholder) placeholder.hidden = true;
        drawing = true;
        context.beginPath();
        context.moveTo(...position(event));
    };
    canvas.onpointermove = (event) => {
        if (!drawing || busy) return;
        event.preventDefault();
        context.lineTo(...position(event));
        context.stroke();
        points += 1;
    };
    canvas.onpointerup = canvas.onpointercancel = () => { drawing = false; };
    form.querySelector('[data-clear]').onclick = () => { if (!busy) clearPad(); };

    form.onsubmit = async (event) => {
        event.preventDefault();
        if (busy) return;
        if (points < 2) { error.textContent = '请先完成手写签字'; return; }
        if (!form.elements[confirmation.name].checked) { error.textContent = '请确认业务信息'; return; }
        ctx.primeAudio();
        busy = true;
        button.disabled = true;
        error.textContent = '';
        button.textContent = '正在提交签字…';
        try {
            const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
            if (!blob) throw new Error('签字图片生成失败');
            const photo = await ctx.photoUpload(field.name, blob);
            await ctx.submit({ [field.name]: photo.fileUrl, evidenceReceipts: { [field.name]: photo.eventId }, [confirmation.name]: true });
        } catch (problem) {
            error.textContent = problem.message;
            button.disabled = false;
            button.textContent = policy.actionLabel || '确认提交';
            busy = false;
        }
    };

    return () => { busy = true; };
}

/* ------------------------------------------------------- 选卡 / 拍照 / 人脸版式 */

function renderCardPhoto(panel, state, ctx) {
    const policy = state.nodePolicy;
    const fields = policy.fields || [];
    const values = ctx.values;
    const cards = values[policy.optionsVar || 'cards'] || [];
    const cardField = fields.find((item) => item.type === 'card');
    const cardIdField = (cardField && cardField.name) || policy.cardIdField || 'selected_card_id';
    const card = cards.find((item) => item.id === values[cardIdField]);
    const selecting = fields.some((item) => item.type === 'card');
    const media = fields.some((item) => item.evidenceKind === 'MEDIA');

    const content = (selecting
        ? `<p class="vb-password-help">${esc(policy.description || '请选择本人名下需要办理的银行卡。')}</p><div class="vb-password-cards">${cards.map((item, index) => `<label class="vb-password-card"><input type="radio" name="${esc(cardIdField)}" value="${esc(item.id)}" required ${index === 0 ? 'checked' : ''}><span><strong>${esc(item.label)}</strong><small>${esc(item.cardNoMasked)}</small></span></label>`).join('')}</div>`
        : `<p class="vb-password-card-caption">办理银行卡：${esc((card && card.cardNoMasked) || '')}</p><p class="vb-password-help">${esc(policy.description || '请按下方要素拍摄并上传清晰完整的照片。')}</p>`)
        + (media && values.mediaMatched === false ? `<p class="vb-node-form-error">${esc(values.mediaMessage)}</p>` : '')
        + fields.filter((item) => item.type === 'photo').map((item) => `<section class="vb-password-photo"><strong>${esc(item.label)}</strong><img data-preview="${esc(item.name)}" alt="${esc(item.label)}预览" hidden>`
            + (item.source === 'face'
                ? `<button type="button" class="vb-photo-button" data-face="${esc(item.name)}">采集本人现场人脸</button>`
                : `<label class="vb-photo-button">拍摄或选择照片<input type="file" accept="image/*" capture="environment" data-photo="${esc(item.name)}"></label>`)
            + `<span data-photo-status="${esc(item.name)}" role="status">尚未上传</span></section>`).join('')
        + fields.filter((item) => item.type === 'text').map((item) => `<details class="vb-mock-details"><summary>${esc(item.label)}</summary><label class="vb-node-field">${esc(item.label)}<input name="${esc(item.name)}" placeholder="${esc(item.placeholder || '选填')}"></label></details>`).join('');

    const note = `${selecting ? '请核对本人银行卡后继续办理。' : '请确认照片清晰完整。'}${values.mockMode ? '本场景为演示核验与办理。' : ''}`;
    panel.innerHTML = formShell(state, policy, content, note);

    const form = panel.querySelector('form');
    const uploads = {};
    const evidenceReceipts = {};
    const pending = new Set();
    const versions = {};
    const current = () => panel.querySelector('form') === form;
    const error = (message) => { if (current()) form.querySelector('.vb-node-form-error').textContent = message; };

    async function upload(name, blob, preview, version) {
        if (!current() || versions[name] !== version) return;
        const status = form.querySelector(`[data-photo-status="${name}"]`);
        status.textContent = '正在上传…';
        const token = {};
        pending.add(token);
        try {
            const data = await ctx.photoUpload(name, blob);
            if (!current() || versions[name] !== version) return;
            uploads[name] = data.fileUrl;
            evidenceReceipts[name] = data.eventId;
            const image = form.querySelector(`[data-preview="${name}"]`);
            image.src = preview;
            image.hidden = false;
            status.textContent = '照片已保存';
        } catch (problem) {
            if (current() && versions[name] === version) {
                status.textContent = '上传未完成，请重新拍摄或选择';
                error(problem.message);
            }
        } finally {
            pending.delete(token);
        }
    }

    form.querySelectorAll('[data-photo]').forEach((input) => {
        input.onchange = async () => {
            const file = input.files[0];
            if (!file) return;
            if (file.size > 8 * 1024 * 1024) return error('照片请控制在8 MB以内');
            const name = input.dataset.photo;
            delete uploads[name];
            delete evidenceReceipts[name];
            const version = versions[name] = (versions[name] || 0) + 1;
            const reader = new FileReader();
            reader.onload = () => {
                const image = new Image();
                image.onload = () => {
                    const scale = Math.min(1, 1600 / Math.max(image.width, image.height));
                    const canvas = document.createElement('canvas');
                    canvas.width = Math.round(image.width * scale);
                    canvas.height = Math.round(image.height * scale);
                    canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
                    canvas.toBlob((blob) => upload(name, blob, canvas.toDataURL('image/png'), version), 'image/png');
                };
                image.onerror = () => error('无法读取照片，请重试');
                image.src = reader.result;
            };
            reader.readAsDataURL(file);
        };
    });

    const face = form.querySelector('[data-face]');
    if (face) {
        face.onclick = async () => {
            try {
                const name = face.dataset.face;
                const version = versions[name] = (versions[name] || 0) + 1;
                delete uploads[name];
                delete evidenceReceipts[name];
                const blob = await ctx.captureFrame();
                await upload(name, blob, URL.createObjectURL(blob), version);
            } catch (problem) {
                error(problem.message);
            }
        };
    }

    form.onsubmit = async (event) => {
        event.preventDefault();
        ctx.primeAudio();
        const button = form.querySelector('[type="submit"]');
        if (pending.size) return error('照片正在上传，请稍候');
        if (selecting && !form.elements[cardIdField]?.value) return error('请选择本人名下的银行卡');
        const payload = { ...uploads, ...(selecting ? { [cardIdField]: form.elements[cardIdField].value } : {}) };
        for (const item of fields.filter((item) => item.type === 'text')) {
            payload[item.name] = form.elements[item.name].value.trim();
        }
        if (Object.keys(evidenceReceipts).length) payload.evidenceReceipts = evidenceReceipts;
        for (const item of fields) {
            if (item.required && !payload[item.name]) return error(`请先完成：${item.label}`);
        }
        button.disabled = true;
        error('');
        try {
            await ctx.submit(payload);
        } catch (problem) {
            button.disabled = false;
            error(problem.message);
        }
    };

    return () => { /* 无长任务持有，表单卸载即结束 */ };
}

/* ------------------------------------------------------------ 通用文本表单版式 */

function renderTextForm(panel, state, ctx) {
    const policy = state.nodePolicy;
    const fields = policy.fields || [];
    const values = ctx.values;
    const receipt = receiptRows(policy.displayFields, values);
    const body = `${receipt}`
        + fields.filter((item) => item.type !== 'hidden').map((item) => {
            const type = item.type === 'checkbox' ? 'checkbox' : item.type === 'number' ? 'number' : 'text';
            const attrs = [
                `name="${esc(item.name)}"`,
                `type="${type}"`,
                item.required ? 'required' : '',
                item.pattern ? `pattern="${esc(item.pattern)}"` : '',
                item.maxLength ? `maxlength="${esc(item.maxLength)}"` : '',
                item.name === 'sms_code' ? 'inputmode="numeric" autocomplete="one-time-code" placeholder="请输入6位验证码"' : '',
                item.name === 'payee_account' ? 'inputmode="numeric" placeholder="请输入收款账号"' : '',
                item.type === 'number' ? 'step="0.01" min="0.01"' : '',
                item.type === 'checkbox' ? '' : `value="${esc(values[item.name] ?? '')}"`,
            ].filter(Boolean).join(' ');
            return item.type === 'checkbox'
                ? `<label class="vb-node-checkbox"><input ${attrs}><span>${esc(item.label)}</span></label>`
                : `<label class="vb-node-field"><span>${esc(item.label)}</span><input ${attrs}></label>`;
        }).join('');

    panel.innerHTML = `<form class="vb-mobile-node-form"><div class="vb-node-form-body">${body}</div><footer class="vb-node-form-footer"><button class="vb-node-primary" type="submit">${esc(policy.actionLabel || '确认')}</button><p class="vb-node-form-error" role="alert"></p></footer></form>`;

    const form = panel.querySelector('form');
    const button = form.querySelector('[type="submit"]');
    form.onsubmit = async (event) => {
        event.preventDefault();
        ctx.primeAudio();
        button.disabled = true;
        const payload = Object.fromEntries(fields.map((item) => [
            item.name,
            item.type === 'checkbox' ? form.elements[item.name].checked : form.elements[item.name].value,
        ]));
        try {
            await ctx.submit(payload);
        } catch (error) {
            button.disabled = false;
            form.querySelector('.vb-node-form-error').textContent = error.message;
        }
    };

    return () => { /* noop */ };
}

/**
 * 场景组件契约：mount(panel, state, ctx) → release 函数；返回 false 表示本组件不接管该节点。
 * ctx 由 node-dialog 提供：{ values, readOnly, submit, photoUpload, captureFrame, primeAudio }。
 */
export function mount(panel, state, ctx) {
    const policy = state.nodePolicy || {};
    const fields = policy.fields || [];
    const actors = policy.actors || [];

    // 历史还原只读（契约 §3.7）：只展示回执，不渲染任何可提交控件。
    if (state.readOnly || ctx.readOnly) {
        const values = ctx.values || { ...(state.vars || {}), ...(state.payload || {}) };
        panel.innerHTML = `<article class="vb-node-form-body"><h3>${esc(policy.title || state.nodeName || '业务记录')}</h3>${receiptRows(policy.displayFields, values)}</article>`;
        return () => {};
    }

    // 非客户节点 / 无表单字段：交由 node-dialog 隐藏弹框（与 navis showMobileForm 同口径）。
    if (!actors.includes('CUSTOMER')) return false;
    if (!fields.length) return false;

    const signatureField = fields.find((item) => item.type === 'signature');
    if (signatureField) return renderSignature(panel, state, ctx, signatureField);

    const hasCardOrPhoto = fields.some((item) => ['card', 'photo'].includes(item.type));
    if (hasCardOrPhoto) return renderCardPhoto(panel, state, ctx);

    return renderTextForm(panel, state, ctx);
}

export default { mount };
