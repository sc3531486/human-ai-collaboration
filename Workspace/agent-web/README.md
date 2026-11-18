# agent-web · 远程视频银行坐席 PC 工作台

坐席侧端壳，契约 [`FRONTEND_VUE3_ARCHITECTURE.md`](../FRONTEND_VUE3_ARCHITECTURE.md)（v1.2）四个独立交付物之一。
技术栈：**JavaScript + Vue 3 + Vite + Element Plus + Pinia**（零 TypeScript，target ES2018+）。
行为基准：`navis-web`（`agent_workspace.html` / `agent_balance.html` / 全部 `agent_*.js` / `node_ui.js` / `nodes/` / `views/`）——
接口路径、请求体、幂等语义、状态机判据逐条对齐，包括带业务原因的边界（契约 §0.4）。

---

## 1. 快速开始

```bash
npm install
npm run dev      # 开发服 http://localhost:5273
npm run build    # 产物输出 dist/
npm run preview  # 预览构建产物
npm run check    # 场景注册自检（20 项断言，见 §6）
```

反代与 `navis-web/server.mjs` + `nginx.conf.example` 语义一致（`vite.config.js`）：

| 路径 | 目标 | 说明 |
|---|---|---|
| `/api` | `BACKEND_URL`（默认 `http://localhost:8080`） | business-svc 等业务接口 |
| `/records` | 同上 | 影像文件（渲染前必须校验 `^/records/[A-Za-z0-9_.-]+$`，§3.7） |
| `/centrifugo` | `CENTRIFUGO_WS_URL`（默认 `http://localhost:8000`） | WebSocket，显式透传 Upgrade 并剥前缀 |

环境变量可覆盖后端地址：`BACKEND_URL`、`CENTRIFUGO_WS_URL`。

---

## 2. 目录说明

```
agent-web/
├── package.json            # vue 3 + vite + element-plus + pinia（脚本见 §1）
├── vite.config.js          # @ 别名 + /api /records /centrifugo 反代
├── index.html              # 唯一页面壳（对应 navis-web 的 agent_workspace.html）
├── scripts/
│   └── check-registry.mjs  # npm run check：场景注册自检（§6）
└── src/
    ├── main.js             # 挂载入口；navis-web 的 4 份 CSS 原样引入，Element Plus 仅作交互基座
    ├── App.vue             # ★ 端壳：只做布局与常驻容器（顶栏四态 / 左栏互斥 / 振铃 / Toast / 视频浮层）
    ├── core/               # ★★ 与场景无关的内核——新增场景时本目录一个字节都不许改
    │   ├── sdk.js          # BankSDK：request 错误判定 + 提交幂等 requestId（源 bank-sdk.js）
    │   ├── trace.js        # BankTrace：本地待发队列，重试沿用 eventId（源 bank-trace.js）
    │   ├── identity.js     # 端身份单一事实源；客户敏感信息不写入地址栏（源 agent_common.js 约束）
    │   ├── format.js       # money / hms / escape / certLabel（源 agent_common.js 口径）
    │   ├── icons.js        # BankIcons 本地 SVG path 表（源 bank-icons.js，未登记名回落 file）
    │   ├── bus.js          # 内核事件总线，替代 window CustomEvent；事件名沿用 navis-web
    │   ├── registry.js     # ★★ 场景自动注册中心（import.meta.glob，§3）
    │   ├── flow/
    │   │   ├── store.js    # 流程快照 store：sequence/epoch 单调收敛唯一判据（源 agent_router.js accept）
    │   │   ├── router.js   # 节点路由：场景包 → GenericNode 兜底 → unmatched（契约 §2.1 五段顺序）
    │   │   ├── sync.js     # 绑定先拉全量快照、DISCONNECTED 才 2s 轮询、generation 丢帧（源 agent_process.js）
    │   │   └── actions.js  # 节点提交唯一出口（组件内禁止直接 fetch，契约 §2.2）
    │   ├── realtime/
    │   │   └── centrifuge.js  # 坐席个人频道：签入只走 HTTP 回写、断线重连（源 agent_realtime.js）
    │   ├── media/
    │   │   └── heartbeat.js + heartbeat.worker.js  # §3.5 在岗心跳：Worker 上报、签出即停（与视频无关，必须保留）
    │   └── video-widget.js   # ★ 视频控件适配层：ESM→UMD 加载 ../video-widget/dist 产物 + §2.9.3 业务侧唯一视频入口
    ├── ui/                 # ★★ 与场景无关的共享组件（新增场景不许改）
    │   ├── GenericNode.vue        # 通用兜底：纯由后端 nodePolicy 驱动（§2.1 第 4 步，"老代码办新业务"的关键）
    │   ├── VbNodeFrame.vue  VbFacts.vue  VbSteps.vue  VbEvidence.vue   # 替代 node_ui.js 的 NodeUI.frame/facts
    │   ├── VbPolicyForm.vue # nodePolicy.fields 驱动的表单，含草稿与提交
    │   └── VbPhotoCapture.vue      # 现场照片采集，走 video.capture()，替代硬编码抓帧
    ├── scenarios/           # ★★★ 场景包：新增场景**只在这里加目录**（§3、SCENARIO_GUIDE.md）
    │   ├── _template/       # 模板（下划线开头 = 模板/私有目录，不参与注册）
    │   ├── transfer/        # 转账汇款  tradeCode 300101
    │   ├── balance/         # 账户查询  tradeCode 200102
    │   └── password/        # 密码服务  tradeCode 200101
    ├── stores/
    │   └── workspace.js     # 坐席工作台状态：§3.5 坐席端红线全部集中在此（515 行，不感知任何场景）
    ├── views/               # 端壳页面，不随场景增长（新增场景不许改）
    │   ├── HomeView.vue  BusinessView.vue  StageView.vue  SideRail.vue
    │   ├── AgentPanel.vue  ApplicantPanel.vue          # 左栏互斥：办理人信息 ⇄ 坐席控制台
    │   ├── CallRing.vue  QueuePop.vue                  # 振铃横条 / 待接队列（同一时刻只一个接听入口）
    │   ├── VideoBar.vue  UnmatchedNode.vue  NoticeCard.vue  CalendarCard.vue
    │   └── HistoryDialog.vue  RestoreDialog.vue  InlineNodeHistory.vue  # 历史还原只读（§3.7）
    └── styles/              # navis-web 原样搬运的 4 份 CSS：类名与配色不改动
```

---

## 3. 场景注册：新增场景的唯一入口

每个场景目录导出一份声明式清单 `src/scenarios/<id>/index.js`，
`core/registry.js` 用 `import.meta.glob('../scenarios/*/index.js', { eager: true })` **构建期自动收集**——
没有路由表、没有注册函数、没有场景计数，任何已有文件都不需要改（契约 §0.3、§2）。

**新增一个场景只需三步（复制 `_template/` 改三处）：**

1. **改清单**：`id` / `tradeCode` / `name` / `icon` / `description` / `processDefinitionKey`；
2. **改 `agent` 映射**：键用 BPMN 里的中文节点名（与 navis-web `AgentRouter.registerPage` 口径一致）；
3. **放组件**：在 `agent/` 下写对应的 `.vue`（props 只有 `state` + `readOnly`，提交一律走 `submitNode`）。

没写组件的节点自动落到 `GenericNode.vue`，纯由后端 `nodePolicy` 渲染推进——**场景包只是定制体验的可选层，不是必需品**。

判断标准与完整走查（含 `core/ui/views` 零改动的证明）见 **[SCENARIO_GUIDE.md](./SCENARIO_GUIDE.md)**。

---

## 4. 与 navis-web 的行为对照

| navis-web（基线） | agent-web 落点 | 保持不变的关键判据 |
|---|---|---|
| `bank-sdk.js` | `core/sdk.js` | `!response.ok \|\| data.success === false` 都抛错；同一 `[processId, input]` 复用 requestId，仅 4xx 清除；`bank:submit-*` 事件 |
| `bank-trace.js` | `core/trace.js` | 失败不丢弃、重试沿用 eventId、串行 flush |
| `agent_router.js` | `core/flow/router.js` + `store.js` | `processId` 不匹配丢弃；epoch 变化重置 lastSequence；sequence 单调收敛；`COMPLETED` → 结果页；切换视图不销毁音视频容器 |
| `agent_process.js` | `core/flow/sync.js` | 绑定先拉全量快照；通道在线完全依赖推送，`DISCONNECTED` 才 2s 轮询；generation 变化丢弃旧响应 |
| `agent_realtime.js` | `core/realtime/centrifuge.js` | 签入状态只经 HTTP 回写，Centrifugo 只承载下行；订阅就绪后才回写状态 |
| `agent_heartbeat.js` / `agent_heartbeat_worker.js` | `core/media/heartbeat.js` / `.worker.js` | 心跳跑在 Worker（后台标签页不被节流）；`OFFLINE`（签出）即停心跳 |
| `agent_workspace.js` | `stores/workspace.js` + `App.vue` + `views/` | 见下方 §4.1 逐条 |
| `node_ui.js` | `ui/VbNodeFrame` `VbFacts` `VbPolicyForm` `VbPhotoCapture` 等 | `node-page` 系 class 与字段渲染口径沿用 `agent_nodes.css` |
| `nodes/*.js`、`views/*.js` 节点页 | `scenarios/<id>/agent/*.vue` | 字段与校验仍来自后端 `nodePolicy`，组件内禁止直接 fetch |
| `AgentRouter.registerPage` 手工登记 | `core/registry.js` 自动注册 | 见 §3；`check` 断言 core/ui/views 无场景名硬编码 |
| `agent_evidence.js` / `bank-media.js` | 独立 `video-widget`（§2.9）经 `core/video-widget.js` 适配层接入 | `capture()` / `record.seal()` 返回值契约；0 字节回执不得宣告成功（§3.4）；无视频仍可办理到底 |
| `bank-icons.js` | `core/icons.js` + `ui/VbIcon.vue` | 本地 SVG path 表，未登记图标回落 `file` |
| `inline-node-history.js` / `history-node-viewer.js` | `views/InlineNodeHistory.vue` / `HistoryDialog.vue` / `RestoreDialog.vue` | 历史还原只读，不叠加聊天/日志/其他节点摘要（§3.7） |
| 后端接口（`server.mjs` 反代语义） | `vite.config.js` | 接口路径与请求体逐条对齐契约 §4，不改后端 |

### 4.1 坐席端红线（契约 §3.5）在本工程的落点

| 红线 | 落点 |
|---|---|
| `hideRing(processId)` 只在命中当前振铃工单时收起 | `stores/workspace.js` `hideRing(message, processId)` |
| 同一时刻只允许一个接听入口（振铃时队列浮层/浮动钮让位） | `App.vue` + `QueuePop.vue`（由 store 互斥计算驱动） |
| 签入状态只经 HTTP 回写 | `core/realtime/centrifuge.js` |
| 心跳在 Worker 中跑、签出即停 | `core/media/heartbeat.worker.js`；`centrifuge.js` `setStatus('OFFLINE') → stopHeartbeat()` |
| 接听 404/409 才回落 `/accept`，其余错误照常抛出 | `stores/workspace.js` `answerCall`（`error.status !== 404 && !== 409` 则 throw） |
| 左栏互斥：办理中「办理人信息」⇄ 其余「坐席控制台」 | `App.vue` `leftPanel`（`layout:left-panel` 事件单点驱动） |
| 关闭业务标签＝`/close` 终结实例 | `stores/workspace.js` `finish()` |

共通红线（§3.7）：敏感信息不入地址栏（`core/identity.js`）、影像 URL 白名单校验与 `escape` 转义（`core/format.js`）、历史还原只读（三个历史视图）。

---

## 5. 已知差异（与 navis-web / 契约的当前状态）

1. **视频控件已接入（`video-widget`，§2.9）**
   **命令口径（显式规则）**：业务/场景代码限 `capture()` / `record.seal()` / `on('state')`（交易逻辑不感知媒体内部）；
   `views/VideoBar.vue` 作为视频控制面（等价 navis 的 toggle-button / hangup-button 工具条，属控件 UI 外壳而非业务逻辑）
   可调 `toggle` / `takeover` / `hangup` / `connect` / `record.start`——且一律经 `core/video-widget.js` 适配层，
   **任何代码不得直接 import 控件 dist**。
   业务侧只 import `core/video-widget.js` 适配层（§2.9.3 唯一入口）：优先动态 ESM 加载
   `../video-widget/dist/vb-video.esm.js`（只吃 dist 产物，不打包控件源码、不跨目录拷贝），
   失败回退 UMD script 注入 `window.VideoBankingWidget`（§8.2 全局名唯一）；样式取 `dist/vb-video.css`
   （UMD 回退时 link 注入，基址 `VITE_VIDEO_WIDGET_BASE`，默认 `/video-widget/`）。
   接听链路在坐席点击接听的用户手势内完成 `connect()`（§3.4 桌面采集手势内申请）+ `record.start()`，
   任一步失败经 `core/bus.js` `report()` 带码如实呈现（错误码 + 人话 + console/`system:error`，不静默、不代偿），
   **无视频时业务仍能办理到底**（§2.9.3）；顶栏通话态由 `on('state')` 订阅驱动。
   端内媒体内核 `core/video/`（controller/layout/VideoStage.vue）与 `core/media/{capture,peer,recorder}.js`
   已移除，check 第 [6] 步断言 `scenarios/views/ui` 层 `MediaStream / MediaRecorder / srcObject /
   getElementById('customer-video')` 零命中，第 [8] 步改为双面断言（适配层 + 控件 dist 产物）命令 API 齐全。
   > ⚠️ `core/media/` 目录里 **`heartbeat.js` + `heartbeat.worker.js` 与视频无关**（§3.5 在岗心跳红线），
   > **必须保留**（`core/realtime/centrifuge.js` 依赖它）。
   > ⚠️ 构建/开发前需 `../video-widget` 已产出 `dist/`（ESM 动态 import 是静态分析的，dist 缺失时构建会直接报错，
   > 这是刻意的：宿主依赖的就是控件 dist 产物，不存在也不需要其他分发方式）。
2. **"core/ui/views 零改动" 用内容快照而非 `git diff` 断言**
   本工作区没有 `.git`，`scripts/check-registry.mjs` 在运行前后对 `src/core`、`src/ui`、`src/views` 逐文件做内容快照比对（等价断言，且不依赖 git 可用性）。若工程处于 git 仓库中，`git status --short src/core src/ui src/views` 同样应为空。
3. **场景清单保留 `mobile` / `chips` 字段**
   与 `channel-web-sdk` 共享同一份清单口径；agent-web 不使用，留空即可，`registry` 对缺字段的一端自动跳过不报错（§2）。
4. **视觉与交互基座的分工**
   `styles/` 下 4 份 CSS 从 navis-web 原样搬运（类名、配色不改）；Element Plus 只承担对话框、下拉、消息等交互基座
   （契约 §0.5 允许 agent-web 保留 Element Plus；`channel-web-sdk` 禁用）。
5. **事件总线**：navis-web 挂在 `window` 上的 `CustomEvent` 派发改为 `core/bus.js` 模块总线，**事件名沿用原命名**
   （`bank:submit-start` / `agent:state` / `agent:connection` …），语义不变。
6. **节点页从字符串拼接改为组件**：`node_ui.js` 的 `NodeUI.frame` / `NodeUI.facts` 内联 HTML 串接拆为
   `VbNodeFrame` / `VbFacts` 等 Vue 组件，字段渲染与校验口径不变。
7. **少量入口为演示占位**：`views/SideRail.vue`（`notReady()`）与 `views/ApplicantPanel.vue`（`hint()`）中
   部分能力按钮当前返回"演示环境暂未接入该能力"提示，未接线真实能力；其余主流程（签入、振铃、接听、办理、
   留痕、历史、统计）均已按基线接线。
8. **错误呈现统一走 `report()`，数据加载失败不回落假数据**（兜底清算收尾）
   所有失败路径经 `core/bus.js` 的 `report(scope, error, { sticky })`：toast 带错误码与人话原因，
   同时落 `console` 与 `system:error`；轮询类（队列 2.5s、指标/统计 15s）带 `sticky` 去重防刷屏
   （console 仍逐次）。首页数据（可办交易/公告/业务记录/当日指标/坐席统计/待接队列）**不再有任何
   "接口不可用回落默认清单/补字段别名"的兜底**：失败带码上报，列表如实为空或保留上一次展示值并明示
   （各 `load*` 函数注释即失败表面说明）。契约明示的规格行为不在此列（§3.5 签入 HTTP 回写与 404/409
   接听回落、§2.9.3 无视频办到底、§6 能力探测降级、`nodePolicy` 通用兜底节点、UMD 加载），
   保留并注明出处；拿不准的项在交付报告中列「待裁定」。

---

## 6. 自检：`npm run check`

`scripts/check-registry.mjs`（node 原生 + 最小 Vite 构建，无外部测试框架），**20 项断言 / 8 步**：

| 步骤 | 断言 |
|---|---|
| [1] 基线 | 场景 ≥3 个；`_template` 未被注册；`transfer` / `balance` / `password` 已自动注册 |
| [2] 快照 | 受保护目录（`src/core`、`src/ui`、`src/views`）采样成功 |
| [3] **新增临时场景**（不改任何已有文件） | 场景被自动注册、场景数 +1、清单字段与 `tradeCode` / `processDefinitionKey` 定位正确、无定制组件节点返回 null（交通用兜底） |
| [4] 零改动 | 新增场景前后 core/ui/views 内容**逐字节一致**，且无文件被删除 |
| [5] 回退 | 删除临时场景后场景列表回到基线，不残留 |
| [6] §2.9 / §8.4 | `scenarios/views/ui` 层禁词零命中（`MediaStream`、`MediaRecorder`、`srcObject`、`getElementById('customer-video'\|'my-video')`、`from '@/core/media/**'`） |
| [7] §0.3 | core/ui/views 无场景 id / 交易代码 / 节点中文名硬编码 |
| [8] §2.9.3 | 适配层 `core/video-widget.js` 与控件产物 `video-widget/dist/vb-video.esm.js` **双面**命令 API 齐全（open/close/connect/hangup/takeover/toggle/capture/record/on/getState），`record.seal()` 存在 |

> 临时场景目录名为 `check_temp_scn`——**不能带下划线前缀**：`registry.collect()` 会跳过 `_` 开头的目录
> （本意是排除 `_template` 等模板/私有目录），带前缀的临时场景永远不会被注册，[3] 步会挂 6 项。

验收口径（契约 §5）：`npm run build && npm run check` 全绿。新增场景后还应复跑一次，确认 [3]～[7] 步仍然通过。
