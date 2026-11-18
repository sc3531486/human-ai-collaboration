/*
 * 节点组件路由（契约 §2.1）。解析顺序严格如下，不得调整：
 *   1) 当前场景包内按 state.nodeName 精确命中
 *   2) 当前场景包内按 state.nodeId 命中
 *   3) 按 processDefinitionKey 命中的场景包（同一流程多场景隔离）
 *   4) 通用兜底 GenericNode —— 纯由后端 nodePolicy 驱动
 *   5) 仍未命中 → unmatched（展示"当前节点尚未配置界面" + 重新匹配按钮）
 *
 * 第 4 步是"老 SDK 能办新业务"的前提（契约 §6.3-1）：后端新增场景，已发布的旧版 SDK
 * 无需升级即可办理；场景包只是可选的美化层。
 */
import registry from '../registry.js';

/** 通用兜底：纯由后端 nodePolicy 渲染并推进流程（选卡 / 拍照 / 签字 / 文本）。 */
export const GenericNode = () => import('../../ui/GenericNode.js');

/** 仍未命中的兜底视图。 */
export const UnmatchedNode = () => import('../../ui/VbUnmatched.js');

function fromScenario(scenario, detail) {
    if (!scenario) return null;
    const table = scenario.mobile || {};
    if (detail.nodeName && table[detail.nodeName]) return table[detail.nodeName];
    if (detail.nodeId && table[detail.nodeId]) return table[detail.nodeId];
    return null;
}

function scenarioHit(detail) {
    if (!detail) return null;
    const scenario = registry.get(detail.scenarioId)
        || registry.byProcessDefinitionKey(detail.processDefinitionKey);
    return fromScenario(scenario, detail)
        || fromScenario(registry.byProcessDefinitionKey(detail.processDefinitionKey), detail);
}

/**
 * 把场景清单里的组件归一成「页面模块工厂」。
 * 两种写法都支持：静态模块对象（manifest 直接 import 页面后放入）与 `() => import(...)` 懒加载。
 */
const toLoader = (hit) => async () => {
    const loaded = typeof hit === 'function' ? await hit() : hit;
    return loaded?.default || loaded;
};

/**
 * 阶段分类：'scenario'＝场景页可接管 / 'generic'＝通用兜底 / 'unmatched'＝未配置视图。
 * 无 nodePolicy 又无场景包时才落 unmatched（§2.1-5）；COMPLETED 的办结回执由端壳先行处理。
 */
export function resolveStage(detail) {
    if (!detail) return 'unmatched';
    if (scenarioHit(detail)) return 'scenario';
    return detail.nodePolicy ? 'generic' : 'unmatched';
}

/**
 * 返回组件加载器。未命中场景包时落到通用兜底 —— 不返回 unmatched，
 * 因为只要后端配了 nodePolicy，通用兜底就能渲染并推进流程。
 */
export function resolve(detail) {
    if (!detail) return UnmatchedNode;
    const hit = scenarioHit(detail);
    return hit ? toLoader(hit) : GenericNode;
}

/** 无 nodePolicy 又无场景包时才真正 unmatched。 */
export function resolveOrUnmatched(detail) {
    if (!detail) return UnmatchedNode;
    if (detail.processStatus === 'COMPLETED') return GenericNode;
    const hit = scenarioHit(detail);
    if (hit) return toLoader(hit);
    return detail.nodePolicy ? GenericNode : UnmatchedNode;
}

export const router = { resolve, resolveOrUnmatched, resolveStage, GenericNode, UnmatchedNode };
export default router;
