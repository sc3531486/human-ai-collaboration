/**
 * 场景模板 —— 渠道端（channel-web-sdk）新增场景时复制本目录改三处即可，不需要改任何已有文件。
 *
 * ── 如何新增一个场景（复制 `_template/` → `src/scenarios/<你的场景id>/`）──
 *
 *  1. 改本文件的 `id` / `tradeCode` / `name` / `icon` / `description` / `processDefinitionKey`；
 *  2. 改 `mobile` 里的节点名 → 页面模块映射（键用 BPMN 里的中文节点名）；
 *  3. 在 `mobile/` 下放对应的 .js 页面模块（export default { mount }）。
 *
 * 做完这三步，registry 会用 import.meta.glob（eager 收集场景目录下的 index.js）
 * 自动发现并注册：路由、快捷问法、通用兜底都会自动包含新场景，
 * `src/core`、`src/ui`、`src/app`、打包配置一行都不用改。
 *
 * ── 页面模块契约 ──
 *   export default {
 *       // 返回释放函数（清理用）；返回 false 表示本页不接管，交回通用兜底；也可省略返回值。
 *       mount(panel, state, ctx) { ... }
 *   };
 *   ctx = { peerId, readOnly, values, primeAudio, photoUpload, captureFrame, submit, rematch }
 *   提交一律走 ctx.submit（内核统一幂等 + 留痕），页面内禁止直接 fetch（契约 §2.2）。
 *
 * ── 没有写页面也能办理 ──
 * 若某节点没有在 `mobile` 里注册，router 会落到通用兜底 GenericNode.js，
 * 纯由后端 nodePolicy（displayFields / fields / actors / title / actionLabel）渲染并推进流程。
 * 场景包只是**定制体验**的可选层，不是必需品（契约 §6.3-1「老 SDK 办新业务」的前提）。
 */

export default {
    // ① 场景身份：id 必须全局唯一（下划线开头的目录不会进入场景列表），tradeCode 与后端交易目录对齐。
    id: '_template',
    tradeCode: '000000',
    name: '场景模板',
    icon: 'file',
    description: '复制本目录改三处即可新增场景',

    // ② 流程定义键：用于把流程快照路由到本场景包（同一流程多场景隔离时也按它定位）。
    processDefinitionKey: '',

    // ③ 渠道端（本 SDK）节点名 → 页面模块；键优先用 BPMN 里的中文节点名。
    //    只写需要定制体验的节点，其余交给通用兜底。
    mobile: {
        // '客户签名': () => import('./mobile/customer-sign.js'),
    },

    // 坐席端（agent-web 填）；本 SDK 只读 mobile 字段，缺 agent 不报错（契约 §2）。
    agent: {},

    // 可选：结果页/播报文案投影。
    resultFields: [],

    // 可选：数字人快捷推荐问法（服务端 rules.quickQuestions 优先）。
    chips: []
};
