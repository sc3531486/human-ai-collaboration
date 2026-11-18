/* 流程完整保留，每次只展示当前节点附近的四个节点。（源：navis-web public/js/flow-window.js） */

export function select(steps = [], nodeId, completed = false) {
    let current = steps.findIndex((step) => step.nodeId === nodeId);
    if (current < 0) current = completed ? steps.length - 1 : 0;
    const start = Math.max(0, Math.min(current - 1, steps.length - 4));
    return {
        before: start,
        after: Math.max(0, steps.length - start - 4),
        nodes: steps.slice(start, start + 4).map((step, offset) => ({ ...step, index: start + offset })),
    };
}

export const FlowWindow = { select };
export default FlowWindow;
