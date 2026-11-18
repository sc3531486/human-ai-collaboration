# 场景包编写指南（channel-web-sdk）

> 目标：**复制 `_template/` 改三处，端到端生效，已有文件零改动**（契约 §2 / §5）。
> 自检命令 `npm run check` 会真实构建临时副本，逐条断言这一点。

## 一、三步新增一个场景

```bash
cp -r src/scenarios/_template src/scenarios/<你的场景id>
```

### ① 改场景身份（`index.js` 顶部字段）

```js
export default {
    id: 'deposit',                       // 全局唯一；下划线开头的目录不进场景列表
    tradeCode: '200103',                 // 与后端交易目录对齐
    name: '定期存款',
    icon: 'wallet',
    description: '存单开立与视频核验',
    processDefinitionKey: 'deposit_sop', // ★ 流程定义键：流程快照按它路由到本场景
    ...
};
```

### ② 改 `mobile` 节点映射（键用 BPMN 中文节点名）

```js
mobile: {
    '客户签名': () => import('./mobile/customer-sign.js'),
    '存款信息确认': () => import('./mobile/deposit-confirm.js'),
},
```

### ③ 在 `mobile/` 下放页面模块

```js
// src/scenarios/deposit/mobile/customer-sign.js
export default {
    // 返回释放函数（清理用）；返回 false ＝本页不接管，交回通用兜底；也可不返回。
    mount(panel, state, ctx) {
        panel.innerHTML = '<form class="mobile-node-form">…</form>';
        const form = panel.querySelector('form');
        form.onsubmit = async (event) => {
            event.preventDefault();
            ctx.primeAudio();                       // 提交手势内解锁音频
            await ctx.submit({ confirmed: true });  // ★ 一律走内核（幂等 + 留痕），禁止直接 fetch
        };
        return () => { /* 释放定时器/监听 */ };
    },
};
```

`ctx` 可用能力：

| 字段 | 说明 |
| --- | --- |
| `ctx.peerId` | 客户端标识 |
| `ctx.readOnly` | 只读态（历史回放）：应只展示、不放提交入口 |
| `ctx.values` | `vars ∪ payload ∪ proposed` 合并值（表单回填用） |
| `ctx.submit(values, action?)` | 内核提交（幂等 requestId + SUBMIT_* 留痕事件），成功后自动套用新状态 |
| `ctx.photoUpload(fieldName, blob)` | 影像上传（`POST /api/evidence/photo`） |
| `ctx.captureFrame()` | 人脸/证件抓帧（控件优先，回落本地预览） |
| `ctx.primeAudio()` | 手势内解锁音频（提交按钮里调用） |
| `ctx.rematch()` | 重新拉取当前状态并渲染（错误页「重新加载」也走它） |

## 二、可选：在通用兜底之上叠加定制

不想重写表单时，用 `withGenericHint` 一行叠加（`src/ui/scenario-kit.js`）：

```js
import { withGenericHint } from '../../ui/scenario-kit.js';
export default withGenericHint('【定期存款】请核对利率与期限后确认。');
```

通用兜底 `GenericNode.js` 纯由后端 `nodePolicy` 渲染：回执行 `displayFields`、表单行 `fields`
（text / number / checkbox / hidden / signature / card / photo 全类型）、按钮 `actionLabel`、
`actors` 不含 `CUSTOMER` 或无字段时自动隐藏弹框。**这就是「老 SDK 办新业务」**：
后端新增节点，前端一行不改也能办理（契约 §6.3-1）。

## 三、解析顺序（`src/core/flow/router.js`，契约 §2.1 严格有序）

```
1) 当前场景包内按 state.nodeName 精确命中
2) 当前场景包内按 state.nodeId 命中
3) 按 processDefinitionKey 命中的场景包（同一流程多场景隔离）
4) 通用兜底 GenericNode（后端 nodePolicy 驱动）
5) 仍未命中 → 「当前节点尚未配置界面」+ 重新匹配按钮
```

- 办结回执（`processStatus === 'COMPLETED'`）优先于一切页面，且**没有关闭入口**；
- 场景页返回 `false` 表示不接管，路由继续按 4) → 5) 兜底，**不会出现空白页**。

## 四、注册机制（为什么零改动）

`src/core/registry.js` 使用契约规定的唯一入口收集场景：

```js
const modules = import.meta.glob('../scenarios/*/index.js', { eager: true });
```

新增目录即被扫描进 `registry`（场景列表、节点路由、快捷问法 `chips` 同时生效）；
`src/core`、`src/ui`、`src/app`、`vite.config.js` **一行都不需要改**。

## 五、端到端验证（无需改任何既有文件）

```bash
npm run check     # ①副本构建：断言 fixture 场景进产物 + 已有文件哈希零变化
npm run build     # ②真实构建三件产物
npm run dev       # ③冒烟：打开页面 → 同意告知书 → 输入触发该场景节点 → 表单出现
```

`npm run check` 断言明细：

1. `src/` 无 `.ts` / `.vue` / `.scss` 残留；glob 写法与契约逐字符一致；
2. 复制仓库到 `.check-tmp/`，**只新增** `src/scenarios/check_fixture_scn/`；
3. 用仓库自带 vite 对副本做一次真实库构建；
4. 构建产物包含 `check_fixture_scn`（自动注册）与既有三场景（未被破坏）；
5. 副本内除新增目录外所有文件 SHA-256 与基线完全一致（零改动）；
6. 仓库本身快照不变，临时目录清理。
