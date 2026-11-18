# SCENARIO_GUIDE —— 渠道侧「新增场景不改任何已有文件」端到端走一遍

> 目标（契约 §0.3 / §6.3 硬约束 1）：**后端新增一个交易场景，渠道宿主（本项目）与已发布的 SDK 一行代码都不用改**。
> 场景包属于 `channel-web-sdk`（`src/scenarios/`），本项目是渠道宿主，本来就没有场景目录——
> 本文按真实接入链路从头走一遍，并给出每一步「哪些文件必须零改动」的核验命令。

## 0. 角色与文件边界

| 角色 | 目录 | 新增场景时是否改动 |
|---|---|---|
| 渠道宿主（本项目） | `enterprise-demo/**` | **永不改动**（见 §3 核验） |
| 渠道 SDK | `channel-web-sdk/src/scenarios/<id>/` | 只**新增**目录，不改已有文件 |
| 后端 | BPMN + `nodePolicy` | 配置新增（流程定义即业务规则） |
| 坐席端 | `agent-web/src/scenarios/<id>/` | 只新增目录（不在本文范围） |

注册机制（契约 §2）：`channel-web-sdk/src/core/registry.ts` 用
`import.meta.glob('../scenarios/*/index.ts', { eager: true })` **自动发现**场景清单——
没有中央注册表要改、没有路由表要改、打包配置不感知场景数量。

---

## 1. 场景包侧：复制 `_template` 改三处

```bash
cp -r channel-web-sdk/src/scenarios/_template channel-web-sdk/src/scenarios/guarantee
```

只改 `guarantee/index.ts` 的清单三处（其余字段按需）：

```js
export default {
    id: 'guarantee',
    tradeCode: '300501',            // ① 交易码
    name: '保函开立',
    processDefinitionKey: 'guarantee_sop',   // ② 流程定义键（对齐 BPMN）
    mobile: {                       // ③ 客户侧节点名 → 页面模块（渠道侧只关心 mobile）
        '保函信息确认': () => import('./mobile/GuaranteeConfirm.vue'),
        '客户签名': () => import('./mobile/SignaturePad.vue'),
    },
    chips: ['开立100万投标保函'],
};
```

**已改动文件：0**（只新增 `guarantee/` 目录）。`channel-web-sdk` 的 `core/`、`ui/`、视图与 `vite.config` 的 git diff 必须为空。

## 2. 后端：BPMN 节点配 `nodePolicy`（老 SDK 能办新业务的前提）

后端为 `guarantee_sop` 流程的每个客户节点配置 `nodePolicy`（`fields` / `displayFields` / `action` 等，契约 §2.1）。
这一步是 §6.3 硬约束 1 的成立条件：

- 节点渲染走 **通用兜底 `GenericNode`**（解析顺序第 4 步）——纯由 `nodePolicy` 驱动；
- 即使场景包一行没写、甚至已发布的**旧版 SDK 没有 guarantee 场景包**，客户也能把业务办理到底；
- 场景包只是**定制体验的可选美化层**。

## 3. 渠道宿主（本项目）：核验零改动

新增场景后，在仓库根目录核验本项目无任何 diff：

```bash
git status --short enterprise-demo/          # 应为空
git diff --stat enterprise-demo/             # 应为空
```

为什么宿主不用改——逐条对应本项目代码：

| 契约条目 | 本项目落点 | 说明 |
|---|---|---|
| SDK 运行时加载（§0.1） | `src/core/sdk-loader.js` | 场景包打进 SDK 产物，宿主只是重新 `npm run vendor:sdk` 拷产物，**接入代码不变** |
| mount(el, config)（§6.1） | `src/views/BankView.vue` | 容器、config、路由与场景无关 |
| 能力桥（§6.2） | `src/core/bridge-host.js` | 场景复用同一批能力域，不新增桥方法 |
| 通用兜底（§2.1/§6.3） | 由 SDK + 后端完成 | 宿主不参与节点渲染 |

> 若只动了后端 + 场景包，连 `npm run vendor:sdk` 都可以不做：旧产物靠 `nodePolicy` 通用兜底照样办理
> （§6.3 硬约束 1 的验收口径）。只有需要**新场景的定制 UI** 时才更新 SDK 产物。

## 4. 端到端演示脚本（验收用）

1. 启动后端 + `navis-web` 坐席端（含新场景的 agent 侧）。
2. `cd enterprise-demo && npm run dev`，进入「远程视频银行」页（桩件或真实产物均可挂载）。
3. 在坐席侧发起 `guarantee` 交易（`POST /api/platform/process`，tradeCode `300501`）。
4. **预期 A（有场景包的新产物）**：客户节点渲染为场景定制 UI（保函信息确认表单）。
5. **预期 B（旧产物 / 未更新产物）**：同一节点由 `nodePolicy` 通用表单渲染，字段、提交、驳回按钮齐全，业务可办结。
6. 复核 §3 的两条 git 命令：`enterprise-demo/` 零改动。

## 5. 判定标准（契约 §0.3）

新增 `guarantee` 场景后：

- `channel-web-sdk/src/core/**`、`src/ui/**`、视图与打包配置 —— **git diff 为空**；
- `enterprise-demo/**` —— **git diff 为空**；
- `agent-web/src/core/**`、`src/ui/**`、`src/views/**` —— **git diff 为空**；
- 变更集合 = `channel-web-sdk/src/scenarios/guarantee/**`（新增）+ `agent-web/src/scenarios/guarantee/**`（新增）+ 后端流程配置。

---

## 附：宿主侧能力示例（与场景正交）

若新场景需要一种**宿主能力**（例如保函打印），宿主只需在 `src/core/bridge-host.js` 的 `device` 域内加方法
（或渠道已有该能力直接开着开关）——这属于「宿主能力替换」（README §4），同样不触碰 SDK 与场景包。
