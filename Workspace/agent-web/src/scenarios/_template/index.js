/**
 * 场景模板 —— 新增场景时复制本目录改三处即可，不需要改任何已有文件。
 *
 * ── 如何新增一个场景（复制 `_template/` → `src/scenarios/<你的场景id>/`）──
 *
 *  1. 改本文件的 `id` / `tradeCode` / `name` / `icon` / `description` / `processDefinitionKey`；
 *  2. 改 `agent` 里的节点名 → 组件映射（键用 BPMN 里的中文节点名）；
 *  3. 在 `agent/` 下放对应的 .vue 组件。
 *
 * 做完这三步，registry 会用 import.meta.glob 自动发现并注册：
 * 可办交易列表、流程路由、结果页都会自动包含新场景，`src/core`、`src/ui`、`src/views` 一行都不用改。
 *
 * ── 组件契约（契约 §2.2）──
 *   props: { state: Object(流程快照，只读), readOnly: Boolean }
 *   提交一律走内核，组件内禁止直接 fetch：
 *     import { submitNode } from '@/core/flow/actions.js';
 *     await submitNode(state, payload, { action: 'REJECT' });
 *   组件卸载清理用 onBeforeUnmount 表达（原 mount() 返回 release 的语义）。
 *
 * ── 没有写组件也能办理 ──
 * 若某个节点没有在 `agent` 里注册，router 会落到通用兜底 `GenericNode.vue`，
 * 纯由后端 nodePolicy（displayFields / fields / actions / actors / view）渲染并推进流程。
 * 场景包只是**定制体验**的可选层，不是必需品。
 */

export default {
    // ① 场景身份：id 必须全局唯一，tradeCode 与后端交易目录对齐。
    id: '_template',
    tradeCode: '000000',
    name: '场景模板',
    icon: 'file',
    description: '复制本目录改三处即可新增场景',
    // ② 流程定义键：用于把流程快照路由到本场景包（同一流程多场景隔离时也按它定位）。
    processDefinitionKey: '',

    // ③ 坐席端节点名 → 异步组件。**键优先用 BPMN 里的中文节点名**，
    //    与 navis-web 的 AgentRouter.registerPage 口径一致。
    //    只写需要定制体验的节点，其余交给通用兜底。
    agent: {
        // '信息采集': () => import('./agent/TemplateCollect.vue'),
    },

    // 客户端节点（mobile-web 侧填写；agent-web 不需要时留空即可，另一端会自动跳过不报错）。
    mobile: {},

    // 可选：结果页/播报文案投影。
    resultFields: [],

    // 可选：数字人快捷推荐问法（仅 mobile-web 用）。
    chips: []
};
