# Centrifugo 实时信令服务端（本地运行）

坐席端的实时推送已从自研原生 WebSocket 迁移到 **Centrifugo v6.9.6**。
本目录用于在**没有 Docker** 的 Windows 开发机上，以官方单二进制方式把信令服务端跑起来。

## 目录内容

| 文件 | 说明 |
| --- | --- |
| `config.json` | Centrifugo v6 配置（**v6 是嵌套结构，与 v5 的扁平结构不兼容**） |
| `start-centrifugo.ps1` | 启动脚本：缺二进制时自动下载解压，然后带 `--config=config.json` 启动 |
| `centrifugo.exe` | 官方 Windows 二进制（由脚本自动下载，**不入库**） |
| `centrifugo_6.9.6_windows_amd64.zip` | 下载缓存（**不入库**），删掉后脚本会重新下载 |

## 快速开始

```powershell
cd D:\WK\cc\新产品市场调研\flowable-poc\centrifugo
.\start-centrifugo.ps1
```

脚本会自动完成：检测 `centrifugo.exe` → 缺失则从 GitHub Release 下载
`centrifugo_6.9.6_windows_amd64.zip` → 解压出可执行文件 → 带 `config.json` 启动。

首次下载约 24 MB，需要能访问 `github.com`。若网络受限，见文末「手动下载」。

> 压缩包内除 `centrifugo.exe` 外还带有 `README.md` / `CHANGELOG.md` / `LICENSE`。
> 脚本刻意先解压到临时子目录、**只取走可执行文件**，避免官方的 `README.md` 覆盖本项目这份文档。

启动成功时日志末尾应出现：

```json
{"level":"info","message":"serving websocket, api, health endpoints on :8000"}
```

## 验证方法

服务启动后，在另一个终端执行：

```powershell
# 1. 健康检查，预期 HTTP 200，返回 {}
curl.exe http://localhost:8000/health

# 2. 确认 8000 处于监听
netstat -ano | findstr ":8000" | findstr LISTENING

# 3. HTTP API 推送，预期 HTTP 200，返回 {"result":{}}
curl.exe -X POST http://localhost:8000/api/publish `
  -H "Content-Type: application/json" `
  -H "X-API-Key: bank-poc-centrifugo-api-key-2026" `
  -d "{\"channel\":\"agent:TEST\",\"data\":{\"hello\":\"world\"}}"
```

以上三条已在 v6.9.6 上实测通过（结果见下节「实测记录」）。
第 3 条若把 `X-API-Key` 换成错误值会返回 **401**，可用来确认 API Key 配置真的生效。

停止服务：在运行脚本的窗口按 `Ctrl + C`。

## 端口与接入约定

| 端口/路径 | 用途 |
| --- | --- |
| `8000` | Centrifugo 的 WebSocket（`/connection/websocket`）、HTTP API（`/api/*`）、健康检查（`/health`） |
| `8000` 的 `/health` | 健康检查，返回 `{}` |
| `8000` 的 `/api/publish` | 后端服务端推送入口，需带 `X-API-Key` |

**坐席端不直连 8000 端口。** 浏览器侧统一走前端 dev server 的反向代理：

```
坐席端浏览器 ──ws://localhost:5173/centrifugo/connection/websocket──> 前端 dev server ──> http://localhost:8000
后端 Spring Boot ──http://localhost:8000/api/publish (X-API-Key)──> Centrifugo
```

即前端需要把 `/centrifugo/*` 反代到 `http://localhost:8000`（并保留 WebSocket 升级头 `Upgrade` / `Connection`）。
这样做的原因：

1. 浏览器只与前端同源通信，避免跨域与混合内容（HTTPS 页面连 `ws://`）问题；
2. 生产环境由反向代理统一收口 TLS、鉴权与访问控制，Centrifugo 不必暴露到公网。

> 注意：当前 `frontend/server.mjs` 只代理了 `/api/`、`/records/` 和 `/ws/`，**尚未包含 `/centrifugo/*`**。
> 接线时需要在前端 dev server 与 `frontend/nginx.conf.example` 中补上该 location。

## 配置说明与安全边界

### 密钥必须与后端保持一致

`config.json` 中的两个值与后端 `src/main/resources/application.yml` 一一对应，**不要自行改动或重新生成**：

| config.json | application.yml | 值 |
| --- | --- | --- |
| `client.token.hmac_secret_key` | `centrifugo.token-secret` | `bank-poc-centrifugo-hmac-secret-2026` |
| `http_api.key` | `centrifugo.api-key` | `bank-poc-centrifugo-api-key-2026` |

后端对应的连接地址为 `centrifugo.api-url=http://localhost:8000`、
`centrifugo.ws-url=ws://localhost:8000/connection/websocket`，token 有效期 3600 秒。

### `allow_subscribe_for_client: true` 是原型阶段的简化

```json
{ "name": "agent", "presence": true, "allow_subscribe_for_client": true }
```

该项为 `true` 时，**坐席端拿 JWT 连上后可以不经验证直接订阅 `agent:*` 频道**，
任意坐席都能订到别人的频道，仅靠频道名做隔离。这是为了让原型阶段先跑通链路而做的简化。

**生产环境必须关闭该开关**（置为 `false` 或直接删除该项），改为在 `connect` / `subscribe`
回调里走 **subscription token 强校验**：由后端按坐席身份签发订阅 token，
在回调中校验 token 与目标频道是否匹配，只放行属于该坐席的频道，并叠加过期的兜底策略。

### 其他原型简化项

- `client.allowed_origins: ["*"]`：Centrifugo 启动时会打印
  `usage of allowed_origins * is discouraged for security reasons` 警告。
  生产应改为**精确的域名白名单**。
- `engine: memory`（默认）：单机内存引擎，无持久化、不支持多实例横向扩展。
  生产多副本部署需要改用 Redis 引擎并配置 broker / presence manager。
- `api-key` 与 `hmac_secret_key` 以明文写在配置文件中，仅限本地原型；
  生产应通过环境变量或密钥管理服务注入。

### 关于 `health` 段

Centrifugo v6 把健康检查挪到了**顶层 `health` 段**，且**默认关闭**。
只写 `http_server.port` 时 `/health` 会返回 404，因此本配置额外加了：

```json
"health": { "enabled": true }
```

这是为了满足 `/health` 探活需要而新增的，不影响其他配置项。

## 排障

| 现象 | 原因与处理 |
| --- | --- |
| `/health` 返回 404 | `health.enabled` 未开启。v6 中该端点默认关闭，确认配置里有顶层 `health` 段 |
| `/api/publish` 返回 401 | `X-API-Key` 与 `http_api.key` 不一致。核对后端 `application.yml` 的 `CENTRIFUGO_API_KEY` 环境变量是否覆盖了默认值 |
| 启动报 `address already in use` | 8000 端口被占用。用 `netstat -ano \| findstr ":8000"` 找到 PID 后结束进程，或改 `http_server.port`（同时要改后端 `api-url` / `ws-url`） |
| 启动报配置解析错误 | v5 的扁平结构（如顶层 `port`、`secret_key`）在 v6 不被识别，必须用本目录的嵌套写法 |
| 坐席端连不上 | 前端 dev server 尚未代理 `/centrifugo/*`，见上文「端口与接入约定」 |
| 脚本下载失败（超时/代理） | 见下方「手动下载」 |
| 中文输出乱码 | 脚本已存为带 BOM 的 UTF-8；若仍乱码，请在 Windows PowerShell 5.1（而非旧版控制台）中运行 |

### 手动下载

若脚本自动下载失败，可手动执行：

1. 下载 <https://github.com/centrifugal/centrifugo/releases/download/v6.9.6/centrifugo_6.9.6_windows_amd64.zip>
   （注意是 `.zip`，**不是** `.tar.gz`）；
2. 解压出其中的 `centrifugo.exe`；
3. 把 `centrifugo.exe` 放到本目录（与 `config.json` 同级）；
4. 重新运行 `.\start-centrifugo.ps1` —— 脚本检测到二进制已存在会直接跳过下载。

## 实测记录

验证环境：Windows 11，Centrifugo v6.9.6（go1.26.8），2026-09-15。

```
$ .\centrifugo.exe version
Centrifugo v6.9.6 (Go version: go1.26.8)

$ netstat -ano | findstr ":8000" | findstr LISTENING
  TCP    0.0.0.0:8000    0.0.0.0:0    LISTENING    10012
  TCP    [::]:8000       [::]:0       LISTENING    10012

$ curl.exe http://localhost:8000/health
{}                                  # HTTP 200

$ curl.exe -X POST http://localhost:8000/api/publish \
    -H "Content-Type: application/json" \
    -H "X-API-Key: bank-poc-centrifugo-api-key-2026" \
    -d '{"channel":"agent:TEST","data":{"hello":"world"}}'
{"result":{}}                       # HTTP 200

$ curl.exe -X POST http://localhost:8000/api/publish -H "X-API-Key: wrong-key" ...
                                    # HTTP 401
```

验证完成后进程已停止，8000 端口不再监听。
