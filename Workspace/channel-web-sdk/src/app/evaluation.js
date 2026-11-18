import { request } from '../core/sdk.js';

/** 客户只评价坐席服务；服务小结由坐席端填写。 */
export function createEvaluation(refs, options = {}) {
    let dialog = null;
    let generation = 0;
    const shown = new Set();
    async function show(call) {
        if (!call?.callId || call.initiatorRole !== 'AGENT' || shown.has(call.callId)) return;
        shown.add(call.callId);
        dialog?.remove();
        dialog = document.createElement('section');
        dialog.className = 'vb-evaluation-overlay';
        dialog.setAttribute('role', 'dialog'); dialog.setAttribute('aria-modal', 'true');
        const card = document.createElement('article');card.className = 'vb-evaluation-card';
        const header = document.createElement('header');header.className = 'vb-evaluation-header';
        const heading = document.createElement('h2');heading.textContent = '服务评价';
        const close = document.createElement('button');close.type = 'button';close.className = 'vb-evaluation-close';close.textContent = '×';close.setAttribute('aria-label', '关闭服务评价');
        header.append(heading, close);
        const summary = options.summary?.() || {};
        const summaryBox = document.createElement('section');summaryBox.className = 'vb-service-summary';
        const status = document.createElement('strong');status.textContent = summary.completed ? '业务办理完成' : '视频服务已结束';
        const detail = document.createElement('span');detail.textContent = summary.name || '远程视频银行服务';
        summaryBox.append(status, detail);
        const canEvaluate = call.initiatorRole === 'AGENT';
        const closeDialog = () => { dialog?.remove();dialog = null;generation++; };
        close.onclick = closeDialog;
        if (!canEvaluate) {
            const done = document.createElement('button');done.type = 'button';done.className = 'vb-evaluation-submit';done.textContent = '关闭';done.onclick = closeDialog;
            card.append(header, summaryBox, done);dialog.appendChild(card);refs.phone.appendChild(dialog);
            return;
        }
        const evaluationTitle = document.createElement('h3');evaluationTitle.textContent = '请为本次服务评分';
        const starsBox = document.createElement('div');starsBox.className = 'vb-evaluation-stars';
        const tagsBox = document.createElement('div');tagsBox.className = 'vb-evaluation-tags';
        const errorBox = document.createElement('p');errorBox.setAttribute('role', 'status');
        const comment = document.createElement('textarea');comment.placeholder = '其他意见（选填）';comment.maxLength = 1000;
        const submit = document.createElement('button');submit.textContent = '提交评价';submit.className = 'vb-evaluation-submit';
        const skip = document.createElement('button');skip.textContent = '暂不评价';skip.className = 'vb-evaluation-skip';
        let stars = null;const selected = new Set();
        const starButtons = [];
        async function loadTags(level) {
            const token = ++generation;selected.clear();tagsBox.replaceChildren();errorBox.textContent = '正在读取评价标签…';submit.disabled = true;
            try {
                const rows = await request('/api/evaluations/tags' + (level == null ? '' : `?stars=${level}`));
                if (token !== generation) return;
                if (!Array.isArray(rows)) throw new Error('评价标签接口返回格式错误');
                for (const row of rows) {
                    const button = document.createElement('button');button.type = 'button';button.textContent = row.label;
                    button.onclick = () => { if (selected.has(row.id)) selected.delete(row.id);else selected.add(row.id);button.classList.toggle('selected', selected.has(row.id)); };
                    tagsBox.appendChild(button);
                }
                errorBox.textContent = rows.length ? '' : '当前星级暂无评价标签，可直接提交星级评价';
                submit.disabled = false;
            } catch (error) { if (token === generation) { errorBox.textContent = error.message; const retry = document.createElement('button');retry.textContent = '重新读取标签';retry.onclick = () => loadTags(stars);tagsBox.appendChild(retry); } }
        }
        for (let level = 1; level <= 5; level++) {
            const button = document.createElement('button');button.type = 'button';button.textContent = '★';button.setAttribute('aria-label', `${level}星`);
            button.onclick = () => { stars = level;starButtons.forEach((item, index) => { item.classList.toggle('selected', index < level);item.setAttribute('aria-pressed', String(index < level)); });void loadTags(level); };
            starButtons.push(button);starsBox.appendChild(button);
        }
        submit.onclick = async () => {
            if (!stars) { errorBox.textContent = '请选择1至5星';return; }
            submit.disabled = true;
            try { await request('/api/evaluations', { callId: call.callId, customerId: options.customerId(), stars, tagIds: [...selected], comment: comment.value });dialog.remove();dialog = null;options.onSaved?.(); }
            catch (error) { errorBox.textContent = error.message;submit.disabled = false; }
        };
        skip.onclick = closeDialog;
        card.append(header, summaryBox, evaluationTitle, starsBox, tagsBox, comment, errorBox, submit, skip);dialog.appendChild(card);refs.phone.appendChild(dialog);
        await loadTags(null);
    }
    return { show, destroy() { generation++;dialog?.remove();dialog = null; } };
}
