# 人机协同 · Human-AI Collaboration

面向银行远程渠道的「人机协同」工作区：数字人/智能体与人工坐席在同一通话与工单流程里协同作业，覆盖接入、编排、媒体、消息、证据留痕与前端多端 SDK。

## 目录结构

```
Workspace/
├── agent-web/            坐席端 Web 应用（Vue3 + Vite）
├── channel-web-sdk/      渠道端 H5 SDK（客户侧接入组件）
├── enterprise-demo/      企业演示前端
├── video-widget/         视频组件（独立构建产物，供各端复用）
├── business-svc/         业务编排服务（Spring Boot）
├── routing-svc/          路由服务
├── signaling-svc/        信令服务
├── media-svc/            媒体服务
├── message-svc/          消息服务
├── workflow-svc/         流程引擎集成（Flowable）
├── centrifugo/           实时消息网关（Centrifugo）配置与启动脚本
├── scripts/              开发运维脚本
├── start-all.ps1         一键启动全部服务
├── stop-all.ps1          一键停止
├── status.ps1            服务状态
└── verify.ps1            端到端验证
customer-profile-service/ 客户画像服务
flowable-poc/             Flowable 流程验证工程
.agents/                  研发流程技能（skills）
```

## 本地启动

```powershell
cd Workspace
./start-all.ps1      # 启动全部微服务与前端
./status.ps1         # 查看运行状态
./verify.ps1         # 端到端验证
./stop-all.ps1       # 停止
```

前置依赖：JDK 17+、Maven 3.9+、Node.js 20+、pnpm/npm。

## 说明

- 构建产物（`target/`、`dist/`、`node_modules/`）、运行日志、本地录制文件与二进制工具不入库，详见 `.gitignore`。
- 各前端子工程 `npm install` 后即可本地起 dev server；后端按 `pom.xml` 聚合构建。

## License

内部研发项目，未公开授权。
