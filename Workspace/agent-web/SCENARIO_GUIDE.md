# SCENARIO_GUIDE · 新增一个场景的端到端走查

本指南用一个具体例子——**「定期存款（deposit）」场景**——从复制模板到验收完整走一遍，
证明契约 §0.3 的核心命题：**新增交易场景不需要改任何已有文件**，
`src/core/**`、`src/ui/**`、`src/views/**` 的改动必须为空。

> 本文记录的输出与校验值来自一次**真实执行**（`npm run check` 20/20、`npm run build` 通过、
> 43 个受保护文件 SHA256 前后一致），不是示意。走查完成后示例场景已删除，仓库恢复 3 场景基线；
> 你可按本文步骤原样复现。

**判断标准（契约 §0.3）**：新增一个场景后，`src/core/**`、`src/ui/**`、`src/views/**` 的 git diff 必须为空。

---

## Step 0 · 记录基线

```powershell
cd agent-web
npm run check        # 记录当前场景列表与断言总数
```

基线状态：

- 已注册场景：`balance, password, transfer`（3 个）；`_template` 作为模板目录不注册。
- 受保护目录 `src/core` + `src/ui` + `src/views` 共 **43 个文件**（走查前后做 SHA256 对比）。

```powershell
# Windows PowerShell：生成受保护目录指纹（走查前跑一次，落盘到临时目录）
$files = Get-ChildItem src/core, src/ui, src/views -Recurse -File
$files | Get-FileHash -Algorithm SHA256 |
    ForEach-Object { "$($_.Hash)  $($_.Path)" } | Sort-Object |
    Out-File -Encoding utf8 $env:TEMP\guarded_before.txt
```

> 若工程处于 git 仓库中，等价地用 `git status --short src/core src/ui src/views`（应为空）作为前后对照。
> 本工作区无 `.git`，`npm run check` 第 [4] 步用内容快照做同样强度的断言。

---

## Step 1 · 复制 `_template/`

```powershell
Copy-Item -Recurse src\scenarios\_template src\scenarios\deposit
Move-Item src\scenarios\deposit\agent\TemplateCollect.vue src\scenarios\deposit\agent\DepositCollect.vue
```

新增内容仅 2 个文件（都在 `src/scenarios/deposit/` 内）：

```
src/scenarios/deposit/
├── index.js                     # 场景清单（Step 2 改三处之 ①②）
└── agent/
    └── DepositCollect.vue       # 节点组件（Step 2 改三处之 ③）
```

**目录名不要以下划线开头**：`core/registry.js` 会跳过 `_` 开头的目录（模板/私有目录约定，
`_template` 即靠它不被注册），下划线开头的场景永远不会进入注册表。

---

## Step 2 · 改三处

### ① 清单身份（`deposit/index.js`）

```js
export default {
    id: 'deposit',                    // 全局唯一
    tradeCode: '400101',              // 与后端可办交易目录对齐（GET /api/platform/trades）
    name: '定期存款',
    icon: 'bank',
    description: '定期开户与转存演示场景',
    processDefinitionKey: 'deposit_sop',   // 把流程快照路由到本场景包
    ...
```

> `tradeCode` / `processDefinitionKey` 必须与后端真实值一致，否则流程快照定位不到场景包，
> 会落到通用兜底（能办但无定制体验）。示例中的 `400101` / `deposit_sop` 为演示值。

### ② `agent` 节点映射

```js
    agent: {
        '信息采集': () => import('./agent/DepositCollect.vue'),
    },
```

**键用 BPMN 里的中文节点名**，与 navis-web `AgentRouter.registerPage` 口径一致。
只登记需要定制体验的节点，其余交给通用兜底。

### ③ 节点组件（`deposit/agent/DepositCollect.vue`）

由 `TemplateCollect.vue` 改名而来，遵守契约 §2.2 组件契约：

```js
const props = defineProps({
    state: { type: Object, required: true },   // 流程快照（只读，勿改）
    readOnly: { type: Boolean, default: false } // 历史还原模式
});
// 提交一律走内核，组件内禁止直接 fetch：
import { submitNode } from '@/core/flow/actions.js';
await submitNode(props.state, values);
```

---

## Step 3 · 验证自动注册

```powershell
npm run check
```

实测输出（节选）：

```text
[1] 基线：registry 能加载并列出已注册场景
  ✓ 已注册场景 ≥3 个：balance, deposit, password, transfer
  ✓ _template 模板目录未被注册为场景
[3] 新增一个临时场景目录（不改动任何已有文件）
  ✓ 临时场景被 registry 自动注册
  ✓ 场景数从 4 增加到 5
  ✓ 临时场景的清单字段被正确解析
  ✓ 可按 processDefinitionKey 定位场景
  ✓ 可按 tradeCode 定位场景
[4] 断言 core / ui / views 零改动
  ✓ core/ui/views 无改动
  ✓ core/ui/views 文件未被删除
结果：20 项通过，0 项失败
```

关键点：

- **`deposit` 出现在注册列表**——没有任何已有文件被修改，`import.meta.glob` 在构建期自动发现它；
- 第 [3] 步 check 自己还会再造一个临时场景 `check_temp_scn`，此时基线 4 → 5，两级自动注册都成立；
- 第 [4] 步在"新增场景进行中"的整个期间对 core/ui/views 做逐字节快照比对，**零改动**。

---

## Step 4 · 验证构建

```powershell
npm run build
```

实测：构建通过，产物中出现独立场景 chunk：

```text
dist/assets/DepositCollect-OrYgN5_S.js   # 场景组件按需分包，不进主包
✓ built in 5.89s
```

主包体积与基线基本一致（场景包是懒加载分包，不随场景数线性膨胀）。

---

## Step 5 · 证明 core/ui/views 零改动

三种等价证明，任选其一（本次走查三者都做了）：

1. **内容指纹**：重跑 Step 0 的指纹命令，与基线 diff——实测 **43 个文件全部一致**：

   ```powershell
   Compare-Object (Get-Content $env:TEMP\guarded_before.txt) (Get-Content $env:TEMP\guarded_after.txt)
   # 无输出 = 无差异
   ```

2. **check 自带断言**：`npm run check` 第 [4] 步「core/ui/views 无改动 / 文件未被删除」（Step 3 输出可见）。

3. **git**（工程在仓库内时）：

   ```powershell
   git status --short src/core src/ui src/views   # 应无输出
   ```

**为什么必须是空的**：`core`（内核）、`ui`（共享组件）、`views`（端壳页面）都对场景数量零感知——
没有路由表要登记、没有场景计数、没有 switch-case。一旦新增场景需要动这三个目录，
说明有场景语义泄漏进了内核，属于契约违规。

---

## Step 6 · 运行时验证（建议，需后端）

```powershell
npm run dev    # http://localhost:5273
```

1. 首页可办交易列表来自 `GET /api/platform/trades`（后端目录里没有的交易不会出现在列表，属正常）；
2. 用与 `tradeCode` 匹配的交易发起流程后，路由按 §2.1 五段顺序解析：
   场景包 nodeName 精确命中 → nodeId → processDefinitionKey → `GenericNode` 兜底 → unmatched；
3. 命中 `信息采集` 时渲染的是 `DepositCollect.vue`；未定制的节点由 `GenericNode.vue` 按 `nodePolicy` 渲染；
4. 历史还原（HistoryDialog / RestoreDialog）中该场景节点只读展示，不触发任何提交。

---

## Step 7 · 回滚走查样例

```powershell
Remove-Item -Recurse -Force src\scenarios\deposit
npm run check     # 场景列表回到 balance, password, transfer（3 个），无残留
```

实测：回滚后 `npm run check` **20/20 通过**、`npm run build` 通过，恢复基线。

---

## 常见问题

| 现象 | 原因与处理 |
|---|---|
| 新场景没出现在 `scenarioIds` 里 | 目录名以下划线开头（`_` 开头被 registry 视为模板/私有目录）；或目录下缺 `index.js` |
| `npm run check` 第 [1] 步场景数不对 | 残留的临时场景目录：check 每次运行会先自动清理 `check_temp_scn`，手工建的场景请确认是否该保留 |
| 流程能办但没有定制页面 | `tradeCode` / `processDefinitionKey` 与后端不匹配，或 `agent` 映射的键与 BPMN 节点中文名不一致 → 落到 `GenericNode` 兜底（不是错误，能办到底） |
| 节点提示「当前节点尚未配置界面」 | 快照既无场景包命中、也无 `nodePolicy`（后端未配置）；点「重新匹配」或补后端 `nodePolicy` |
| 第 [6] 步禁词命中 | 场景/视图/组件层出现了 `MediaStream`、`MediaRecorder`、`srcObject`、`getElementById('customer-video')` 或 `from '@/core/media/**'`——业务侧只许依赖 `core/video-widget.js` 适配层的 `capture()` / `record.seal()` / `on('state')`（契约 §2.9.3） |
| 第 [7] 步硬编码命中 | 内核（core/ui/views）里写了场景 id、交易代码或节点中文名——这些只能出现在 `scenarios/` 的清单与组件里 |
