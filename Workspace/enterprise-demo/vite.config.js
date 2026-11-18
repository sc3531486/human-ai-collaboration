import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { readFileSync } from 'node:fs';

/*
 * 反代语义与 navis-web/server.mjs + nginx.conf.example 对齐（契约 §5）：
 *   /api/         → Spring Boot 后端（nginx: proxy_pass http://bank-backend:8080）
 *   /records/     → 同一后端（影像文件）
 *   /centrifugo/  → Centrifugo WebSocket（nginx 显式 Upgrade 透传）
 *   /ws           → 消息服务 STOMP（客户渠道实时通道，mobile-realtime.js 连接的同源路径）
 * 反代只透传，所有业务规则由后端执行；此处不写任何业务逻辑。
 *
 * SDK 产物运行时加载（契约 §0.1 / §6.1）：不在此处 alias 或 import channel-web-sdk 源码，
 * 产物经 scripts/copy-sdk-dist.mjs 拷入 public/vendor/ 后由 loader 运行时读取。
 */
const backend = process.env.BACKEND_URL || 'https://localhost:8080';
const centrifugo = process.env.CENTRIFUGO_WS_URL || 'ws://localhost:8000';
const message = process.env.MESSAGE_WS_URL || 'ws://localhost:8085';
const https = process.env.VITE_HTTPS === 'true'
    ? { pfx: readFileSync(fileURLToPath(new URL('../business-svc/src/main/resources/keystore.p12', import.meta.url))), passphrase: process.env.TLS_PFX_PASSWORD || 'password' }
    : undefined;

export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
        host: '0.0.0.0',
        port: Number(process.env.PORT || 5274),
        https,
        proxy: {
            '/api': { target: backend, changeOrigin: true, secure: false },
            '/records': { target: backend, changeOrigin: true, secure: false },
            '/centrifugo': {
                target: centrifugo,
                ws: true,
                changeOrigin: true,
                // 对外同源前缀不是 Centrifugo 的服务端路径，转发前必须去掉。
                rewrite: path => path.replace(/^\/centrifugo/, ''),
            },
            '/ws': { target: message, ws: true, changeOrigin: true },
        },
    },
    build: {
        outDir: 'dist',
        emptyOutDir: true,
        // 契约 §8.1：应用 target ES2018+。
        target: 'es2018',
    },
});
