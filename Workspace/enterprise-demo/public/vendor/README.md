# vendor/channel-web-sdk/

此目录存放 `channel-web-sdk/dist` 的构建产物，由 `npm run vendor:sdk`（或 `predev`/`prebuild` 自动）拷贝：

    ../channel-web-sdk/dist/vb-banking.umd.js  →  public/vendor/channel-web-sdk/
    ../channel-web-sdk/dist/vb-banking.esm.js   →  public/vendor/channel-web-sdk/
    ../channel-web-sdk/dist/vb-banking.css      →  public/vendor/channel-web-sdk/

应用在运行时按序探测：UMD（script → window.VideoBanking）→ ESM（动态 import）→ 桩件。

**本目录内容是生成物，不要手工编辑、不要提交**（见 `.gitignore`）。
channel-web-sdk/dist 未就绪时本目录为空属正常现象，应用会自动使用接口对齐的桩件 SDK。
