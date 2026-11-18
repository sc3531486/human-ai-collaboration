# vb-banking · 渠道方集成 SDK（channel-web-sdk）

数智化远程视频银行的**客户侧渠道 SDK**：零 TypeScript、零框架（禁 Vue / Element Plus / Pinia），
原生 DOM + 原生 ES Module 实现。宿主页面只需一个容器：

```html
<link rel="stylesheet" href="./vb-banking.css">
<script src="./vb-banking.umd.js"></script>
<script>
    const sdk = window.VideoBanking.mount('#vb-container', {
        apiBase: '',            // 同源（或指向 business-svc）
        channelId: 'h5-01',     // 渠道标识（消息令牌扩展字段，契约 §6.3-2）
        tenantId: 'default',
        widgetUrl: './vb-video.umd.js'   // 视频控件 UMD（可选，见下文）
    });
    sdk.startBusiness();        // 建立实时通道并开始办理
    // sdk.destroy();           // 离场时全量拆卸（SDK 只拥有容器，不接管宿主路由）
</script>
```

## 1. 产物与构建

| 产物 | 说明 |
| --- | --- |
| `dist/vb-banking.esm.js` | ESM 入口（`import { mount } from 'vb-banking'`） |
| `dist/vb-banking.umd.js` | UMD 全局 `window.VideoBanking`（契约 §8.2「UMD 全局名唯一性」） |
| `dist/vb-banking.css` | 全部样式：所有选择器挂在 `.vb-root` 下、类名 `vb-` 前缀，无任何全局污染（§6.3-3） |

```bash
npm install          # 仅 devDependency：vite（运行时零依赖）
npm run build        # 库模式：ESM + UMD，target ES2018+，minify esbuild（CSP 无 unsafe-eval）
npm run dev          # 本地调试（http://localhost:5173），启动后请冒烟并停掉进程
npm run css          # 由 navis-web 源样式重新生成 src/styles/vb-banking.css（提交产物，勿手改）
npm run check        # 场景注册自检：新增场景目录零改动断言 + 副本真实构建
```

## 2. 公共 API（契约 §1 / §6.1）

```js
import { mount, startBusiness, destroy, configure, registry, bus, EVENTS,
         quality, identity, trace, probeCapabilities, video, format } from 'vb-banking';

const sdk = mount(el, config);   // el：选择器或元素；同一时刻只允许一个实例
await sdk.startBusiness();       // 能力门禁（§8.2）→ 实时通道 + 首次校准 + 轮询兜底
await sdk.destroy();             // 断通道、停录音、放流、清容器
```

`config`：

| 字段 | 说明 |
| --- | --- |
| `apiBase` | 后端前缀，缺省空串＝同源（经渠道方或 Vite 反代） |
| `channelId` / `tenantId` | 消息令牌扩展字段（`POST /api/message/token`） |
| `customerId` | 客户端身份覆盖，缺省 `cust-` + 6 位随机（与 navis-web 同口径） |
| `processId` | 首屏已有流程时直连；否则由首条意图识别返回后自动绑定 |
| `bridge` | 宿主原生桥实例（等价 `installBridge()` / `window.VBBankingBridge`） |
| `widgetModule` / `widgetUrl` | 视频控件 ESM 模块或 UMD 地址 |

## 3. 运行环境能力（契约 §8.2）

`probeCapabilities()` 显式回报缺失项，**降级不静默**：

- 缺 `fetch` / `WebSocket` / `Promise` → `startBusiness()` 抛人话错误；
- 缺 `getUserMedia` → 宿主桥 `media.acquire` → 画布模拟流（演示模式）；
- 缺 `speechSynthesis` → 仅文字展示；缺 `WebAssembly` → 编解码走 bridge 质量档。

## 4. JSBridge（十域契约）

宿主实现 `window.VBBankingBridge`（或 `config.bridge`）后，SDK 自动优先走原生能力；
**按方法调用返回 `null` 即回落 Web 实现**。完整方法签名见 [`src/bridge/contract.md`](src/bridge/contract.md)：

`media`（取流/放流） · `album`（选图/拍照） · `ocr` · `face` · `signature` · `record`（存证追加）
 · `biometrics` · `device`（读证/打印/扫码，**无 Web 回落**，降级人工录入） · `navigate` · `telemetry`

样式全部 `vb-` 前缀、容器内 `.vb-root` 作用域，宿主页面与 SDK 互不影响。

## 5. 视频控件（video-widget，契约 §2.9）

SDK 通过 `src/core/video-widget.js` 适配器以 §2.9.3 指令 API 驱动控件：
`video.open / close / connect / hangup / takeover / toggle / capture / record.start / record.seal / on / getState`，
以及 `mountInto(容器)`（主舞台落位）。控件 UMD 全局为 `window.VideoBankingWidget`（与本 SDK 的
`window.VideoBanking` 不冲突）；加载用「注入前后引用 diff + 特征探测」防覆盖。控件缺失时 SDK 只做舞台图层
与文案渲染，业务流程照常办理；抓帧回落本地预览，封存返回 `fileSize === 0` 时**如实告警，绝不宣告成功**。

### 5.1 冒烟联调结果（node，`npm test`，2026-02 全绿 13/13）

`tests/widget-smoke.test.mjs` 用**真实控件产物** `../video-widget/dist/vb-video.esm.js` 经适配层驱动，
注入 fake 传输（`registerTransport('smoke')`）与内存版 `indexedDB / fetch / MediaRecorder` 替身，覆盖：

| 环节 | 结论 |
| --- | --- |
| 挂载降级 | node 无 DOM 时 `mountInto` 返回 `null` 不抛错 |
| `open` | `layout` 只接受布局对象，字符串 `'audio'` 被拦截（否则被摊成 `{0:'a',…}`） |
| `connect` | `agentPeerId/processId/localStream/desktop:false` 正确翻译到控件 `{peerId,…}` 口径；`hasLocal/hasRemote/call/connection==='connected'` 状态齐 |
| 事件转发 | `adapter.on('state')` 收到控件状态快照（订阅在 `ensure()` 后自动接到控件） |
| `takeover` | 仅转发 `av / audio / released` 三值，非法值返回 `null` |
| `capture` | 无画面时如实 `null`，不伪造 Blob |
| `record` | idle 报「当前没有进行中的录制」；非 0 回执 `success:true`；**0 字节 ⇒ `success:false` + 「该段未采集到画面」**；封存后再 seal 不吐旧回执 |
| `hangup` | 注入流 track 全部 `ended`（§8.4-5），`connection==='ended'` |
| 降级 | `teardown` 后 `ensure()/capture()/record.seal()` 全部安全回 `null` |

### 5.2 WebView 真机验证步骤清单（node 无法覆盖，需真机/浏览器逐项打勾）

1. **stage 渲染**：宿主容器 `mountInto` 后控件 DOM 落位、dock 布局比例正确、宿主自建控件条（`toolbar:false`）不出现控件工具栏。
2. **同意手势内取流**：同意告知书前不得出现摄像头指示灯；「同意并开始服务」手势后 `getUserMedia` 才被调用（§3.6 红线）。
3. **UMD 注入装载**：配置 `widgetUrl` 指向 `vb-video.umd.js`，`window.VideoBankingWidget` 挂载成功且不覆盖 `window.VideoBanking`（引用 diff 防覆盖生效）。
4. **真实抓帧**：连通后 `video.capture('local')` 返回非空 `Blob`（图像可见），未连通时回落本地预览画布帧。
5. **真实封存非 0**：真 MediaRecorder 分片 + `/api/balance/record/stream-chunk` 上传成功，`seal` 回执 `fileSize > 0 && success === true`；断网场景回执 `success === false` 且队列保留行。
6. **挂断停轨**：`hangup` 后 `stream.getTracks().every(t => t.readyState === 'ended')`，浏览器摄像头/麦克风指示灯熄灭。
7. **等待音乐联动**：坐席接入（`state.call === true`）后等待音乐停止（`wait-tone` 判据 `videoConnected` 走 `call/hasRemote/connection` 字段）。

### 5.3 换音视频厂商的扩展约定（高内聚低耦合落点）

- **控件内部换厂商**：在控件的 `registerTransport(name, Adapter)` 注册表内完成（widget-dev 范围），SDK 零改动。
- **控件整体替换**：§2.9.3 命令面是唯一契约接缝——`src/core/video-widget.js` 是**唯一**允许出现控件命令签名与状态快照字段名的文件，且已语义化收敛（`open / connect / capture / record.* / isConnected`）。上层 `video-session / recording / instance` 只消费语义接口，不读控件 schema：换控件时只改接缝适配层。
- **会话与台账分层**：接入/释放/挂起续接在 `app/video-session.js`，开段/封存/办结告警在 `app/recording.js`，`instance.js` 只做装配编排。
- **判据单一事实源**：人工视频节点与等待坐席判据（§3.6 红线）收敛在 `core/flow/policy.js`，接入与等待音乐同源，不再各写一份。


## 6. 关键红线（§3.6 / §3.7）

- **同意告知书之前不取任何媒体权限**；点击「同意并开始服务」的手势内解锁 AudioContext、
  speechSynthesis、语音识别与等待音乐。
- 节点业务弹框在节点完成前**没有关闭入口**；办结由 Flowable 推送，**无「确认办结」按钮**。
- 表单去重键 `[processId,nodeId,revision,agentPeerId,action,fields]`：仅当弹框确已打开且确已渲染才跳过。
- 等待音乐判据：`RUNNING && 人工视频节点 && takeover_mode !== 'released' && 未接入 && 未连通`；
  接管（`takeover_mode`）只做渲染，不反向驱动视频。
- 敏感信息不进 URL；影像地址白名单 `^/records/[A-Za-z0-9_.-]+$`（PDF `.pdf$`）；一切用户/模型字符串进 DOM 前转义。

## 7. 开发代理（与 navis-web/server.mjs、nginx.conf.example 语义一致）

| 路径 | 目标 | 环境变量 |
| --- | --- | --- |
| `/api`、`/records` | business-svc `http://localhost:8080` | `BACKEND_URL` |
| `/ws`（STOMP） | message-svc `ws://localhost:8085` | `MESSAGE_WS_URL` |
| `/centrifugo` | Centrifugo `ws://localhost:8000` | `CENTRIFUGO_WS_URL` |

## 8. 目录结构

```
src/
├── index.js          # 公共 API（mount / startBusiness / destroy / …）
├── main.js           # 开发调试入口（npm run dev）
├── app/              # 端壳编排：shell / consent / speech / wait-tone / takeover /
│                     #   media / chat / node-dialog / ai-config / instance /
│                     #   video-session(控件会话状态机) / recording(录像分段台账)
├── core/             # 内核：sdk(接口) bus trace registry quality identity format
│   ├── flow/         #   store(单调收敛) router(§2.1 五步) sync(HTTP校准+STOMP+轮询) steps
│   │                 #   policy(§3.6 判据单一事实源：人工视频节点 / 等待坐席)
│   ├── realtime/     #   stomp.js（/ws 通道，令牌含 channelId/tenantId）
│   └── video-widget.js  # §2.9.3 控件指令适配（唯一允许出现控件命令签名与状态字段的接缝）
├── bridge/           # JSBridge 十域：index.js + contract.md
├── scenarios/        # ★ 场景包：新增场景只在这里加目录（_template 可直接复制）
├── ui/               # GenericNode / VbUnmatched / VbSteps / VbIcon / receipt / dom / scenario-kit
└── styles/vb-banking.css  # 由 scripts/build-css.mjs 生成（勿手改）

tests/
├── helpers/widget-fixtures.mjs  # 共用替身：假流/假传输/假 MediaRecorder/内存 IDB
├── widget-smoke.test.mjs        # SDK↔控件冒烟链（真实控件产物 dist/vb-video.esm.js）
├── recording.test.mjs           # 录像分段台账红线回归（0 字节/回执缺失分开陈述）
└── policy.test.mjs              # §3.6 判据回归（接入与等待音乐同源）
```

新增场景（零改动注册）请看 [`SCENARIO_GUIDE.md`](SCENARIO_GUIDE.md)；
自检命令 `npm run check` 会真实构建一个临时副本并断言「只新增目录、已有文件零改动」。
