# bank-video-widget · 远程视频银行独立视频控件

契约：`FRONTEND_VUE3_ARCHITECTURE.md` **§2.9 独立视频控件**（v1.2）。
第 4 份独立交付物：**零 TypeScript、零 Vue** 的原生 JavaScript 控件——命令 API + 视图层 + 传输抽象 + 分段录制，
由 agent-web / channel-web-sdk 以 ESM 打包或 UMD `<script>` 方式加载，**不属于任何业务场景**。

- 内核纯 JS、不碰 DOM、不持有 `MediaStream`（流全部经 surface 抽象注入）→ node 单测可跑全链路。
- 视图层是原生 JS（`src/stage.js`），渲染进宿主给的容器元素；样式全在 `.vb-` 前缀下。
- 与 navis 基线（`navis-web/public/js/bank-media.js`、`agent_evidence.js`、`mobile_balance.html`）的行为对照见文末。

## 1. 交付产物（`npm run build`）

| 文件 | 形态 | 用途 |
| --- | --- | --- |
| `dist/vb-video.esm.js` | ESM（target **ES2018+**，无 ES5/IE11 降级） | 有打包器的宿主（agent-web）直接 `import` |
| `dist/vb-video.umd.js` | UMD，全局名 **`window.VideoBankingWidget`** | 无打包器宿主 `<script>` 注入 |
| `dist/vb-video.css` | 纯 CSS（`.vb-` 前缀，不污染宿主） | 无论哪种加载方式都要引入 |

> §8.2 全局名唯一性（lead 裁定）：`window.VideoBanking` 归 channel-web-sdk 的 `vb-banking.umd.js` 所有；
> video-widget 的 UMD 挂 `window.VideoBankingWidget`，两份产物同页共存互不覆盖。

## 2. 加载方式

### 2.1 ESM（agent-web 等有打包器的宿主）

```js
// 打包器里可直接用包名；不用打包器时用 dist 文件的 URL
import { mount, destroy, version, video } from 'bank-video-widget';
// 样式：import 'bank-video-widget/style.css';   （或单独 link 标签）

const stage = mount(document.getElementById('video-box')); // → VideoStage 实例
await video.open({ mode: 'overlay', layout: { left: 0.72, top: 0.05, width: 0.26, height: 0.3 } });

// 建链必须发生在用户手势内（同意告知书/点击接听后）：
await video.connect({ peerId: 'cust-1', processId: 'P1' });

await video.hangup();
stage.destroy();   // 或 destroy()（批量卸载）
```

### 2.2 UMD（channel-web-sdk / 纯页面）

```html
<link rel="stylesheet" href="vb-video.css">
<script src="vb-video.umd.js"></script>
<script>
    const stage = VideoBankingWidget.mount(container);   // mount(container, options?)
    VideoBankingWidget.video.open({ mode: 'dock' });     // 命令 API 在 .video 上
    // 普通 <script type="module"> 也可直接 import ESM 产物
</script>
```

`mount` 与命令 API **解耦**：可以先 `mount` 再 `open`，也可以完全不 `mount`（无 UI 场景由宿主自建承载面，
调 `controller.useSurface(mySurface)` 把画面接到自己的元素上）。

## 3. 命令 API（契约 §2.9.3，唯一入口 `src/index.js`）

业务侧**只 import 入口**，不得下钻 `src/**`。

| 命令 | 说明 |
| --- | --- |
| `await video.open({ mode, layout, pixel, draggable })` | 挂载控件；**只管状态与布局，不申请任何媒体权限** |
| `await video.close()` | 卸载并释放（停录制、停合成、释放轨道、清空 surface） |
| `await video.connect({ peerId, processId })` | 建链；本地流 + `getDisplayMedia` 都在这次用户手势内申请；`peerId` 缺省走本地见证（不订阅，`call` 保持 false） |
| `await video.hangup()` | 挂断：封存录制 → 停传输 → **停全部 track**（§8.4-5），状态回 `ended` |
| `await video.takeover('av' \| 'audio' \| 'released')` | 接管模式，仅接受服务端下发的三种值，其它值抛错；控件不下业务判断 |
| `await video.toggle('audio' \| 'video')` | 本地静音 / 暂停画面，返回切换后布尔值 |
| `await video.capture('remote' \| 'local')` | ★ 抓帧 → `Blob \| null`；**无画面返回 null，不伪造占位图**（契约 §2.9.3 只有这两个源，其它源抛错） |
| `await video.record.start({ processId, bizNo, nodeName, composite, ... })` | 开始一段录制，返回 `{ recordingId }`；**已采集桌面且未显式传 `composite: false` 时必须三分屏存证**——画布/captureStream/混音不可用如实报错（不静默改录原始流；node/NullSurface 环境请显式 `{ composite: false }`） |
| `await video.record.seal()` | ★ 封存 → `{ success, fileSize, recordingId, chunks, duration, message? }`，见红线 |
| `await video.record.resume()` | 页面重载后按同一 `recordingId` 补传队列中待传分片 |
| `const off = video.on('state', cb)` / `video.off(...)` | 状态订阅，返回取消函数 |
| `video.getState()` | 状态快照（纯数据，**无 MediaStream**） |

状态字段：`mounted / mode / layout / pixel / draggable / connection / call / recording / takeover /
audioEnabled / videoEnabled / hasRemote / hasLocal / hasDesktop / usingMockStream / recordingId /
peerId / processId / bizNo / error`。

扩展点（同一入口导出）：`useTransport(逻辑名)`（换厂商只改注册行，§6.4）、`useSurface(承载面)`、
`resolveBox / moveTo / setLayout / restoreLayout`（布局口径与 `layout.js` 单一来源）、
`registerTransport / injectStream`（渠道方已有流直接注入；**传输必须具备注入能力**，
如 `useTransport('injected')`——否则如实抛错，不再静默跳过却把 `call` 置真）。

> **传输四方法契约（W1/W2）**：`connect()` 由 controller 在 `publish` 之前统一驱动——第三方适配器
> 按基类契约实现的 `connect` 不再是死代码；实现**必须幂等**（P2P 的 subscribe 自举会二次调用，
> 见 `P2PTransport.connect` 的双 guard）。`connect({ peerId, processId })` 传的是**对端**上下文，
> 本地 peer-id 只从构造 options 取。由此注入式传输建链后快照 `connection` 诚实落 `connected`
> （修复前整通恒为 `idle`），`hangup` 后为 `ended`——按 `connection` 做质检/时长统计不再漏。

### 视图层（`mount` 返回的 stage）

```js
const stage = mount(container, {
    toolbar: true,                 // 底部工具栏（抓拍/静音/挂断/拖柄）
    showLocalPreview: true,        // 本地 PiP 小窗
    zIndex: 2000,
    placeholder: '等待客户接入…',
    icons: { capture: '…' },       // 可换成任意文本或内联 SVG
    onCapture: blob => {}, onHangup: () => {}
});
stage.on('move', layout => {});    // 拖拽落位（归一化值，已持久化）
stage.on('capture', blob => {});
stage.on('error', error => {});
stage.measure();                   // 容器变化自动重算，必要时可手动
stage.destroy();
```

- overlay：归一化布局换算成像素 + 边界 clamp，可拖拽（回写**归一化值**，换分辨率仍在相对位置）。
- dock：退化为 `order + heightRatio` 参与宿主流式布局（§2.9.2）。
- 自动播放被浏览器策略拦截时出「开启画面与声音」兜底按钮，而不是黑屏。

## 4. 录制红线（§3.4 / §8.4，测试逐条锁死）

1. **空分片同样上报**：`size === 0` 的分片不被过滤（`ondataavailable` 与首片登记都计数上报）。
   加 `if (blob.size > 0)` 会重现「录满 40 秒、落盘 0 字节却报存证成功」。
2. **先 IndexedDB 落盘、再顺序上传**：失败不清行，重试沿用同一 `recordingId + chunkIndex`（= navis 同一 eventId）。
3. **重载补传同 `recordingId`**：`record.resume()` 把队列排空。
4. **`fileSize === 0` 如实返回、`success` 恒为 `false`**，`message = '该段未采集到画面'`——不得宣告成功。
5. `getDisplayMedia` 只在用户手势（接听）内申请；`open()` 之前零媒体权限。
6. 合成与显示同口径 `object-fit: cover`（画布 `drawCover`），不拉伸变形。
7. `hangup` / `close` / 卸载停全部 track，不留僵尸采集轨道。

## 5. 构建与测试

```bash
npm install
npm run build        # vite lib → dist/vb-video.esm.js + dist/vb-video.umd.js + dist/vb-video.css
npm test             # node --test "tests/**/*.test.mjs"（Windows 不接受目录参数，用 glob）
npm run ci           # build + test
```

- 测试直接 import `src/**`（内核不依赖构建产物）；`tests/*.test.mjs`，node 内置 runner。
- 断言基准 = navis 行为：**空分片必报、0 字节不得宣告成功** 的断言不允许反向修改。
- 当前：129 项全绿。

## 6. 与 navis 基线的行为对照

基准文件：`navis-web/public/js/bank-media.js`（队列/上传）、`agent_evidence.js`（uploadChain/stopAndUpload）、
`mobile_balance.html`（移动端封存回执）。

| 行为 | navis 基线 | 本控件 | 结论 |
| --- | --- | --- | --- |
| 空分片 | `ondataavailable` 不按 size 过滤，enqueue 入队即传 | `ChunkQueue.enqueue` 不校验 size；首片登记空分片 | ✅ 一致（红线） |
| 落盘顺序 | `put → flush → 成功删行，失败保留行` | `ChunkQueue.flush` 同构，排序按 `startedAt + chunkIndex` | ✅ 一致 |
| 重试编号 | 读到的仍是同一行（同 recordingId+chunkIndex） | 同，`flush(true)` 失败时抛出、行保留 | ✅ 一致 |
| 上报链 | `uploadChain = uploadChain.then(() => enqueue(item))`，`stopAndUpload` 先 `await uploadChain` 再 `flush()` | `SegmentRecorder.uploadChain` 同构；`seal()` 先等链排空再 flush | ✅ 一致 |
| 持续上报失败 | fire-and-forget，不打断录制，行留库补传 | 同（链上 catch，`onError` 上报状态） | ✅ 一致 |
| 封存回执 | 移动端 `json.fileSize \|\| 0`，`fileSize===0` → 「未采集到画面」提示 | `seal()` 返回含 `fileSize` 的回执，0 字节 → `success:false` + `message:'该段未采集到画面'` | ✅ 一致（判定内置为 `success`） |
| 补传 | 重载后同 recordingId 续传 | `record.resume()` → `queue.flush()` | ✅ 一致 |
| 桌面采集时机 | `getDisplayMedia` 在接听手势内 | `connect()` 内申请 | ✅ 一致（§3.4） |
| 合成画幅 | `drawCover`（object-fit cover） | `capture.js` 同口径；CSS `object-fit: cover` | ✅ 一致 |
| 断开释放 | 结束时停轨道 | `hangup/close/destroy` 停全部 track | ✅ 一致（§8.4-5） |

### 已知差异（3 处，均有意为之）

1. **`flush()` 空队列的返回值**：navis `flush()` 本轮无片可传时返回 `null`；本控件回退返回
   `lastReceipt`（最近一次成功上传的回执）。原因：navis 的 `stopAndUpload` 忽略回执，而本控件的
   `seal()` 必须拿到**最后一片**的回执——最后一片往往在录制期间就已上传完，封存时队列已空，
   返回 `null` 会把 `fileSize` 丢成 0。一次都没成功过仍返回 `null`。
2. **`seal()` 的 `success` 加工**：navis 直接透传服务端回执；本控件在透传基础上追加
   `success = Boolean(receipt.success) && fileSize > 0`（§3.4 要求 0 字节不得宣告成功），
   并补 `message` 供业务直接展示。`fileSize` 本身仍如实透传、不补默认值。
3. **结构形态**：navis 是页面内全局函数（`BankMediaQueue` 单例）；本控件是依赖注入的类
   （`ChunkQueue` / `SegmentRecorder`，可注入 fake IndexedDB / upload 直接单测），
   且 `seal()` 后清 `recordingId`——再次 `seal()` 返回「当前没有进行中的录制」而不是旧回执。

## 7. 目录结构

```
video-widget/
├── src/
│   ├── index.js            # ★ 唯一公开入口：mount / destroy / version + 命令 API 再导出
│   ├── controller.js       # 命令 API 状态机（§2.9.3），零 DOM
│   ├── layout.js           # 布局口径：归一化/像素/clamp/dock（单测覆盖）
│   ├── stage.js            # 原生 JS 视图层（VideoStage），唯一碰 <video> DOM 的业务处
│   ├── styles.css          # .vb- 前缀样式 → dist/vb-video.css
│   ├── media/
│   │   ├── recorder.js     # ChunkQueue + SegmentRecorder（红线实现）
│   │   ├── capture.js      # TripleScreenCompositor + drawCover 抓帧
│   │   ├── local.js        # getUserMedia / getDisplayMedia 申请与释放
│   │   └── surface.js      # NullSurface / DomSurface（内核与 DOM 的隔离层）
│   └── transport/          # MediaTransport 抽象 + p2p/injected 注册表 + PeerJS 装载（§6.4）
├── tests/                  # node --test *.test.mjs（129 项）
├── vite.config.js          # lib 模式三件产物，ES2018+，UMD 全局 VideoBankingWidget
└── README.md
```

与契约 §2.9 目录的对应：`recorder.js`（在 `media/`）、`compositor.js`（= `media/capture.js`）、
`styles.css`（= `src/styles.css`）；文件布局按运行时职责演进，入口与产物形态严格按契约。

## 8. 已知事项

- 原 `package.json` 引用的 `vendor.mjs`（拷贝分发到 agent-web / channel-web-sdk）**在本目录并不存在**，
  相关脚本已移除；分发改为宿主直接依赖 `dist/` 产物。若仍需「目录内 vendor 拷贝」分发方式，由 lead 统一裁定。
- `typecheck` / `build:watch` 等 tsc 脚本随 TypeScript 一并移除（本控件零 TS）。
- 传输实测基于 PeerJS 注入（`peer-loader.js`）；生产厂商接入经 `registerTransport(逻辑名, Adapter)` 一行替换，
  业务文件不出现厂商字样（§6.4）。
