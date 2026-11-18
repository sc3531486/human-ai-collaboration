/*
 * 未命中视图（契约 §2.1 第 5 步）：节点既无场景页、后端也没下发 nodePolicy 时展示。
 * 不提供"忽略"入口——重新匹配会触发一次全量快照校准，等待后端补上下文。
 */
import { escape } from '../core/format.js';
import { icon } from './VbIcon.js';

export function mount(panel, state, ctx = {}) {
    panel.innerHTML = '';
    const wrap = document.createElement('div');
    wrap.className = 'vb-unmatched node-form-body';
    wrap.innerHTML = `<header><h3>当前节点尚未配置界面</h3></header>
        <p class="password-help">业务节点「${escape(state?.nodeName || state?.nodeId || '未知')}」正在办理中，界面配置稍后同步。您也可以重新匹配一次。</p>`;
    const actions = document.createElement('div');
    actions.className = 'vb-unmatched-actions';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'node-primary';
    button.appendChild(document.createTextNode('重新匹配'));
    button.appendChild(icon('refresh', 16));
    button.onclick = () => { if (typeof ctx.rematch === 'function') ctx.rematch(); };
    actions.appendChild(button);
    wrap.appendChild(actions);
    panel.appendChild(wrap);
    return () => {};
}

export default { mount };
