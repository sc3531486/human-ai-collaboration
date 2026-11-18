# enterprise-demo —— 企业网银渠道宿主示例（Vue 3）

渠道方视角的集成示例：演示**企业网银（PC Web 渠道）如何集成 `channel-web-sdk`**。
本项目是四个独立交付物之一（契约 §0.1），与 `agent-web`、`channel-web-sdk`、`video-widget` **互不 import 源码、不建 workspace 包**。

- 技术栈：JavaScript + Vue 3 + Vite（零 TypeScript，target ES2018+，契约 §8.1）
- 不引入 Element Plus：示例页仅需表格与开关，宿主自有样式已覆盖（契约 §6.3 禁止 SDK 侧引入，宿主侧亦无必要）
- 写作用域：仅 `enterprise-demo/**`

---

## 1. 快速开始

```bash
cd enterprise-demo
npm install
npm run dev        # http://localhost:5274 （predev 自动尝试拷贝 SDK 产物）
npm run build      # 产出 dist/（prebuild 同样自动拷贝）
npm run check      # 最小自检：桥十能力域 + 开关语义 + 桩件接口 + 文档存在
npm run preview    # 预览构建产物
```

环境变量（可选）：`BACKEND_URL`（默认 `http://localhost:8080`）、`CENTRIFUGO_WS_URL`（默认 `ws://localhost:8000`）、`MESSAGE_WS_URL`（默认 `ws://localhost:8085`）、`PORT`（默认 `5274`）。

---

## 2. 目录说明

```
enterprise-demo/
├── package.json / vite.config.js / index.html
├── scripts/
│   ├── copy-sdk-dist.mjs   # 把 channel-web-sdk/dist 拷入 public/vendor/channel-web-sdk/
│   └── check.mjs           # 最小自检（npm run check）
├── public/vendor/          # SDK 产物落点（生成物，git 忽略；见 public/vendor/README.md）
└── src/
    ├── main.js             # 应用入口
    ├── App.vue             # 端壳：渠道自有顶栏 / 路由（hash）/ footer —— 不随 SDK 变化
    ├── styles/host.css     # 宿主自有样式（SDK 样式收在容器内，互不污染）
    ├── core/
    │   ├── sdk-loader.js   # ★ 运行时加载 SDK：UMD → ESM → 桩件（§0.1 / §6.1）
    │   ├── mock-sdk.js     # 桩件 SDK：mount(el, config) 接口对齐，桥优先 + 浏览器兜底
    │   ├── bridge-host.js  # ★ 宿主 JSBridge mock（§6.2 十能力域 + contract.md §10 network）
    │   ├── bridge-state.js # 能力开关共享状态（buildBridge()）
    │   ├── bridge-log.js   # 调用日志：native / browser 两条路径
    │   └── capability-demo.js # 能力自检：以 SDK 视角逐域调用并记录路径
    └── views/
        ├── HomeView.vue      # 渠道首页（不挂 SDK）
        ├── BankView.vue      # ★ mount(el, config) 挂载页：SDK 只拥有 #sdk-container
        └── CapabilityView.vue# 能力矩阵开关 + 调用日志
```

---

## 3. 接入步骤（渠道方照此接入 SDK）

### 3.1 拿到 SDK 构建产物

```bash
# 由本项目脚本拷贝（predev / prebuild 已自动执行，也可手动）：
npm run vendor:sdk
```

脚本把 `channel-web-sdk/dist/vb-banking.umd.js`、`vb-banking.esm.js`、`vb-banking.css` 拷到
`public/vendor/channel-web-sdk/`。**不拷源码、不做打包器级 import**——这是契约 §0.1 的红线。

### 3.2 运行时加载（`src/core/sdk-loader.js`）

按序探测：

1. **UMD**：`<script src="/vendor/channel-web-sdk/vb-banking.umd.js">` → `window.VideoBanking`（§8.2，兼容 `window.VBBanking`）
2. **ESM**：动态 `import('/vendor/channel-web-sdk/vb-banking.esm.js')`
3. **都缺席** → 接口对齐的桩件（`src/core/mock-sdk.js`），保证 `channel-web-sdk` 未构建时示例仍可运行

> 探测产物存在必须校验 `content-type: javascript`：SPA 回退会把缺失路径以 `200 text/html` 返回，只看 `res.ok` 会把 index.html 当脚本加载。

### 3.3 挂载（`src/views/BankView.vue`）

```js
const instance = sdk.api.mount(document.getElementById('sdk-container'), {
    apiBase: '',                    // 同源；反代见 §5
    channel: 'enterprise-web',
    channelId: 'ent-web-001',
    tenantId: 'tenant-demo',
    customerId: 'cust-0001',
    bridge: buildBridge({           // 宿主能力：config.bridge 优先于 window.VBBankingBridge
        onClose: () => closeSdk(),  // 宿主路由：SDK 不控制宿主页面（§6.1）
        onNavigate: (target) => { window.location.hash = '#/' + target; },
    }),
    onEvent: (type) => { /* ... */ },
});

// 离开页面 / 宿主主动收起：
instance.destroy();
```

要点：

- **SDK 只拥有 `#sdk-container`**：顶栏、hash 路由、footer、会话全部是渠道方的；SDK 换了、页面不用改。
- **容器出现时机由宿主决定**：进入页面挂载，离开即 `destroy()`（本示例 `onBeforeUnmount` 保证不留后台句柄）。

---

## 4. 替换宿主能力（两条路径怎么换）

桥契约见 `channel-web-sdk/src/bridge/contract.md`；十能力域：`media / album / ocr / face / signature / record / biometrics / device / navigate / telemetry`（另有可选 `network`）。

| 场景 | 做法 |
|---|---|
| 渠道已实现某能力（如手机银行已有人脸核身） | 在 `bridge` 对象里提供该域方法 → SDK 走**宿主原生路径** |
| 渠道未实现某能力 | **不提供该域**（对象里没有这个 key）→ SDK 自动走**浏览器兜底**，业务不阻断 |
| 想临时验证兜底链路 | 「宿主能力」页把开关关掉 → 回「远程视频银行」重新挂载 |

实现位置：

- `src/core/bridge-host.js` —— 每个域一个 `wrap(id, methods)`；`createHostBridge(enabled)` 按开关删除域。
- 换成真实原生桥：把各域方法体替换为 App 注入的调用（或直接暴露 `window.VBBankingBridge`，此时 `config.bridge` 可省略）。
- 红线（contract.md §0）：方法返回 `Promise`；宿主实现抛错按「未实现」降级，业务不被宿主缺陷卡死；二进制用 `Blob`；失败 `throw new Error(人话文案)`。

**验证**：「宿主能力」页 → 「运行能力自检」→ 日志里每条记录 `宿主原生` 或 `浏览器兜底`。

---

## 5. Vite 反代（与 navis-web/server.mjs + nginx.conf.example 语义一致，§5）

`vite.config.js`：

| 路径 | 目标 | 对齐依据 |
|---|---|---|
| `/api` | `BACKEND_URL`（默认 `http://localhost:8080`） | nginx `location /api/` → bank-backend:8080；server.mjs `/api/` 分支 |
| `/records` | 同上 | nginx `location /records/`；server.mjs `/records/` |
| `/centrifugo`（ws） | `CENTRIFUGO_WS_URL`（默认 `ws://localhost:8000`） | nginx `location /centrifugo/` 显式 Upgrade 透传；server.mjs `upgrade` 分支 |
| `/ws`（ws） | `MESSAGE_WS_URL`（默认 `ws://localhost:8085`） | 客户渠道 STOMP 同源路径（mobile-realtime.js 连 `/ws`） |

反代只透传，业务规则全部在后端执行；生产部署照 `navis-web/nginx.conf.example` 写法即可。

---

## 6. 与 navis-web 的行为对照与已知差异

对齐项：

- **同意告知书之前不请求媒体权限**（§3.6）：桩件 SDK 的媒体按钮在同意前禁用；宿主桥只在被调用时取流。
- **桥未实现 → 浏览器兜底，不阻断业务**（contract.md §0/§6.2），含 `biometrics` 未实现时**跳过不臆造结果**。
- 反代路径与语义同 `server.mjs` + `nginx.conf.example`（§5）。
- SDK 容器作用域样式、`vb-` 前缀：桩件样式带 `vb-` 前缀、只作用于 `.vb-host` 容器内。

已知差异 / 待联调：

1. **桩件先行**：`channel-web-sdk/dist` 就绪前用 `mock-sdk.js`（接口按 §6.1 + contract.md 对齐）；产物就绪后 `npm run vendor:sdk` 自动切换，宿主代码零改动。
2. 桩件不含真实业务流程（聊天、节点表单、录像分段上传的 IndexedDB 队列），只演示**挂载生命周期 + 桥两条路径**；这些由真实 SDK 承担。
3. 桩件「申请视频流」在无摄像头环境回落到画布模拟流（与 navis-web `createMobileMockStream` 同口径，仅原型演示）。
4. 本示例不接 STOMP 实时通道（客户侧 `/ws`）——实时同步是 SDK 内部行为，宿主只负责反代可用。
5. `network` 域为 contract.md §10 可选能力，开关默认开；渠道无自有票据时应关闭。

---

## 7. 场景相关

本项目是**渠道宿主**，不含 `src/scenarios/`（场景包在 `channel-web-sdk` 与 `agent-web` 内）。
渠道侧「新增场景不改宿主」的端到端证明见 [SCENARIO_GUIDE.md](./SCENARIO_GUIDE.md)。
