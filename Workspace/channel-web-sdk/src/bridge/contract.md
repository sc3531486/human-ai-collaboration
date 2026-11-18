# JSBridge 宿主能力契约

SDK 只定义接口、由渠道方实现；未实现的能力一律走 Web 兜底，SDK 不得因宿主缺失能力而阻断业务。

十能力域（契约 §6.2，与 `src/bridge/index.js` 一一对应）：

`media` · `album` · `ocr` · `face` · `signature` · `record` · `biometrics` · `device` · `navigate` · `telemetry`

## 0. 通用约定

- 所有方法返回 `Promise`；宿主未实现或返回 `null` / `undefined` 时，SDK 自动降级到 Web 实现。
- 所有方法必须**幂等且可重入**：SDK 可能并发调用（例如同时发起取流与抓帧）。
- 宿主实现挂载到 `window.VBBankingBridge`，或在 `mount(el, config)` 的 `config.bridge` 里传入。
- 二进制一律用 `Blob` 传递，不用 base64（手机银行 WebView 上大图 base64 会显著卡顿）。
- 任何失败用 `throw new Error(message)` 表达，`message` 会直接展示给客户，请写人话。
- 后端地址与渠道令牌走 `mount(el, config)` 的 `apiBase` / `channelId` / `tenantId` 配置，不设 network 域。

## 1. media —— 取流

App WebView 的 `getUserMedia` 在 iOS WKWebView 与部分 Android 机型上不可用，必须由宿主提供原生取流。

```js
media.acquire(constraints?: {
  video?: boolean | { facingMode?: 'user' | 'environment'; width?: number; height?: number };
  audio?: boolean;
}): Promise<MediaStream>
media.release(stream: MediaStream): Promise<void>
```

- 未实现 → SDK 退化为 `navigator.mediaDevices.getUserMedia`；再失败 → 退化为画布模拟流（仅原型演示用）。
- **红线：SDK 在客户同意告知书之前不调用本方法**（契约 §3.6）。宿主侧若有权限预检，也应在同意之后触发。

## 2. album —— 相册选图

```js
album.pick(options?: { maxSizeMB?: number; source?: 'album' | 'camera' }): Promise<Blob>
```

- 未实现 → 降级为 `<input type="file" accept="image/*">`。
- SDK 侧统一做 8MB 上限校验与等比压缩（长边 1600），宿主可不做。

## 3. ocr —— 证件识别

```js
ocr.recognize(image: Blob, kind: 'ID_FRONT' | 'ID_BACK' | 'BANK_CARD'): Promise<{
  cardNo?: string; name?: string; idNo?: string; raw?: Record<string, unknown>;
}>
```

- 渠道方通常已有人脸核身与 OCR，复用不重写。未实现 → SDK 不阻断，改由后端能力接口识别。

## 4. face —— 人脸核身

```js
face.verify(image: Blob, options?: { threshold?: number }): Promise<{
  score: number; passed: boolean; threshold: number; auditId?: string;
}>
```

- 未实现 → 交由后端 `password.identity.v1` 能力接口判定。

## 5. signature —— 签字板

```js
signature.open(options?: { title?: string }): Promise<Blob>   // 返回签字图片
```

- 未实现 → SDK 用 Canvas 手写板（`ui/VbSignaturePad.js`）。

## 6. record —— 录像存储

```js
record.append(chunk: Blob, meta: {
  bizNo: string; processId: string; segmentIndex: number;
  handlerType: string; handlerName: string; nodesCovered: string; isLast: boolean;
}): Promise<{ fileSize: number; fileHash?: string; fileUrl?: string }>
```

- 未实现 → SDK 走 Web 分片上传（`video-widget` 的 recorder 负责，IndexedDB 队列 + `/api/balance/record/stream-chunk`）。
- **空分片同样上报**（`size > 0` 过滤会导致录满 40 秒、落盘 0 字节却报成功）。
- 封存回执 `fileSize === 0` 时 SDK 如实提示"该段未采集到画面"，**不得宣告存证成功**。

## 7. biometrics —— 生物认证

```js
biometrics.auth(reason: string): Promise<{ passed: boolean; method?: string }>
```

- 用于高风险交易二次确认。未实现 → 跳过，不做降级替代（不臆造认证结果）。

## 8. device —— 自助外设（智能柜台 / STM）

```js
device.readIdCard(): Promise<{ name: string; idNo: string; photo?: Blob }>   // 身份证阅读器
device.print(payload: { title: string; rows: Array<{ label: string; value: string }> }): Promise<{ printed: boolean }>   // 凭条打印
device.scan(): Promise<{ value: string }>                                     // 扫码枪
```

- 纯硬件能力，**无 Web 兜底**：宿主未实现时返回 `null`，调用方降级为人工录入 / 拍照识别 / 提示"请使用渠道方 App 扫码"。
- SDK 不感知具体机型；自助设备渠道的差异全部收敛在宿主这一层实现里。

## 9. navigate —— 宿主路由

```js
navigate.close(): Promise<void>                    // 关闭 SDK 容器
navigate.to(target: string, params?: object): Promise<void>   // 跳转宿主页面
```

- 未实现 → SDK 不主动跳转；办结只展示结果，不提供"确认办结"按钮（契约 §3.6）。

## 10. telemetry —— 埋点

```js
telemetry.log(event: string, payload?: Record<string, unknown>): Promise<void>
```

- 未实现 → 丢弃，不影响业务。
