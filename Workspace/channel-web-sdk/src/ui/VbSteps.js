/*
 * Flowable 流程进度图：按需弹出，办结自动收起（源：mobile_balance.html renderDiagram）。
 * 只渲染服务端下发的 steps / history，不自行推断业务状态。
 */
import { escape } from '../core/format.js';
import { select } from '../core/flow/steps.js';

/** 渲染到 stepper 容器；state 缺失或无步骤时隐藏容器。 */
export function render(container, state) {
    if (!container) return;
    if (!state || !(state.steps || []).length) {
        container.style.display = 'none';
        container.classList.remove('vb-visible');
        return;
    }
    const steps = (state.steps || []).filter((step) => step.mobileVisible !== false);
    if (!steps.length) {
        container.style.display = 'none';
        container.classList.remove('vb-visible');
        return;
    }
    const done = new Set((state.history || []).filter((row) => row.completed).map((row) => row.nodeId));
    const view = select(steps, state.nodeId, state.processStatus === 'COMPLETED');
    const currentVisible = steps.some(step => step.nodeId === state.nodeId);
    container.style.display = 'flex';
    container.classList.add('vb-visible');
    container.innerHTML = `<div class="vb-stepper-header"><span>${currentVisible ? '业务流程' : escape(state.nodeName || '正在办理')}</span><small>${steps.length}个客户操作阶段</small></div><div class="vb-stepper-steps">${view.nodes.map((step) => `<div class="vb-step ${done.has(step.nodeId) ? 'vb-done' : step.nodeId === state.nodeId ? 'vb-active' : ''}"><div class="vb-circle">${done.has(step.nodeId) ? '✓' : step.index + 1}</div><div class="vb-label">${escape(step.nodeName)}</div></div>`).join('')}</div>`;
}

/** 办结收起（源：closeDiagram 的 slideUpFade，动画名对应生成样式的 vb-slideUpFade）。 */
export function close(container) {
    if (!container) return;
    if (!container.classList.contains('vb-visible')) return;
    try {
        container.style.animation = 'vb-slideUpFade 0.4s ease forwards';
        setTimeout(() => {
            container.style.animation = '';
            container.style.display = 'none';
            container.classList.remove('vb-visible');
        }, 420);
    } catch {
        container.style.display = 'none';
        container.classList.remove('vb-visible');
    }
}

export const VbSteps = { render, close };
export default VbSteps;
