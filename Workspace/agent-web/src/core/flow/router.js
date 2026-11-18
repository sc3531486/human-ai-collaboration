/**
 * 节点路由：节点名 → 组件 + 通用兜底。
 *
 * 解析顺序严格按契约 §2.1：
 *   1) 当前场景包内按 state.nodeName 精确命中
 *   2) 当前场景包内按 state.nodeId 命中
 *   3) 按 processDefinitionKey / tradeCode 命中的场景包（同一流程多场景隔离）
 *   4) 通用兜底 GenericNode —— 纯由后端 nodePolicy 驱动
 *   5) 仍未命中 → unmatched，展示「当前节点尚未配置界面」+ 重新匹配按钮
 *
 * 业务视图切换**不销毁音视频容器**：音视频由 §2.9 的独立视频控件持有，生命周期跟随业务而非节点；
 * 这里只负责节点组件的挂载与释放（对应 AgentRouter 里 cleanup 只清理上一个节点组件）。
 */
import { ref, shallowRef, markRaw } from 'vue';
import { flowState, acceptSequence, belongsTo } from './store.js';
import { resolveScenarioComponent } from '../registry.js';
import GenericNode from '@/ui/GenericNode.vue';
import { bus, report } from '../bus.js';

const RESULT_PAGE = '业务办理结果';

export const activeComponent = shallowRef(null);
export const activeKey = ref('');
export const activeProps = ref({});
export const unmatched = ref(false);
export const unmatchedReason = ref('');

let release = null;
let preferredScenario = null;

/** 场景包优先解析：由端壳在绑定业务时按交易/流程设置（同一流程多场景隔离）。 */
export function preferScenario(scenario) {
    preferredScenario = scenario || null;
}

function drain() {
    if (typeof release === 'function') {
        try { release(); }
        catch (error) {
            // 清理失败不能顶掉后续路由，但必须可见：残留的上一节点资源是内存/计时器泄漏源。
            report('节点组件清理', error);
        }
    }
    release = null;
}

function pick(detail) {
    // 办结统一落到结果视图：旗下所有场景共用同一份结果呈现。
    if (detail.processStatus === 'COMPLETED') return { key: RESULT_PAGE, component: GenericNode };
    const loader = resolveScenarioComponent(detail, preferredScenario);
    if (loader) return { key: `${detail.nodeName || detail.nodeId}`, component: loader };
    // 通用兜底：只要 BPMN 节点配了 nodePolicy，前端没写任何场景代码也能办理。
    if (detail.nodePolicy) return { key: `generic:${detail.nodeId || detail.nodeName}`, component: GenericNode };
    return null;
}

/** 异步组件加载器可能是 () => import(...)，统一求值到组件定义。 */
async function resolve(target) {
    const loaded = await (typeof target === 'function' ? target() : target);
    return loaded?.default || loaded;
}

export async function route(detail, readOnly = false) {
    const found = pick(detail);
    if (!found) {
        drain();
        activeComponent.value = null;
        activeKey.value = '';
        unmatched.value = true;
        unmatchedReason.value = detail.nodeName || detail.processStatus || '';
        bus.emit('agent:route-unmatched', detail);
        return false;
    }
    unmatched.value = false;
    unmatchedReason.value = '';
    // 同一节点重复命中只更新 props，不重建组件，避免表单草稿与拍照预览被清掉。
    if (activeKey.value === found.key) {
        activeProps.value = { state: detail, readOnly };
        bus.emit('agent:view-update', detail);
        return true;
    }
    // 组件加载失败如实抛出：返回 false 会让调用方误读成"该帧未被采纳（过期帧）"，
    // 真实原因（chunk 丢失/模块语法错误）只有抛出才到得了调用方与全局错误面。
    const resolved = await resolve(found.component);
    if (!resolved) throw new Error(`节点组件未解析出定义：${found.key}`);
    drain();
    activeComponent.value = markRaw(resolved);
    activeKey.value = found.key;
    activeProps.value = { state: detail, readOnly };
    bus.emit('agent:routed', { key: found.key, state: detail });
    return true;
}

/**
 * 接收一帧流程快照。
 * @returns {boolean} 该帧是否被采纳（未被采纳通常是过期或重复帧，属正常现象）。
 */
export function accept(detail, force = false, readOnly = false) {
    if (!detail || !belongsTo(detail.processId)) return false;
    // sequence / epoch 单调收敛判据由 store 统一裁决，路由层不重写一份。
    const accepted = force ? forceAccept(detail) : acceptSequence(detail, false);
    if (!accepted) return false;
    // accept 是同步判据（帧是否采纳），组件加载是异步的：加载失败不能吞掉，统一落到
    // report（toast + system:error）。采纳与否已经由上面的序号判据裁决完毕，不受加载影响。
    route(detail, readOnly).catch(error => report('节点界面加载', error));
    return true;
}

/** 强制接纳同一快照重放（force=true），用于提交后立刻刷新与手动刷新。 */
function forceAccept(detail) {
    if (!detail || !Number.isSafeInteger(detail.sequence)) return false;
    if (flowState.epoch && detail.epoch !== flowState.epoch) flowState.lastSequence = -1;
    return acceptSequence(detail, true);
}

/** 绑定业务：清空当前视图并复位序号（对应 AgentRouter.bind）。 */
export function bind(processId) {
    drain();
    activeComponent.value = null;
    activeKey.value = '';
    activeProps.value = {};
    unmatched.value = false;
    unmatchedReason.value = '';
    flowState.processId = processId;
    flowState.lastSequence = -1;
    flowState.epoch = '';
    flowState.snapshot = null;
}

/** 用当前快照重算视图（场景异步加载完成、或点击「重新匹配」后调用）。 */
export function refresh(readOnly = false) {
    const current = flowState.snapshot;
    if (!current) return Promise.resolve(false);
    return route(current, readOnly);
}

/**
 * 历史还原：返回可渲染的组件与只读 props，不触碰当前路由、表单与通话。
 * 与 AgentRouter.renderHistorical 同语义：节点未配置界面时抛错，由调用方降级为「暂无界面」。
 */
export async function renderHistorical(detail) {
    const loader = resolveScenarioComponent(detail, preferredScenario) || GenericNode;
    const resolved = await resolve(loader);
    if (!resolved) {
        // 契约明示的空状态（§2.1 第 5 步语义）：打上专用错误码，调用方可与真实加载失败区分。
        const error = new Error(`节点「${detail.nodeName || detail.nodeId}」未配置展示页面`);
        error.code = 'NODE_UNCONFIGURED';
        throw error;
    }
    return { component: markRaw(resolved), props: { state: detail, readOnly: true } };
}

export function releaseCurrent() {
    drain();
}

export default { accept, route, bind, refresh, renderHistorical, preferScenario, releaseCurrent, activeComponent, activeKey, activeProps, unmatched, unmatchedReason };
