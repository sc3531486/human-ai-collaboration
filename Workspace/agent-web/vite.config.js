import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';
import { readFileSync } from 'node:fs';

/**
 * 反代与 navis-web/server.mjs + nginx.conf.example 语义一致：
 *   /api 与 /records → 业务后端；/centrifugo → Centrifugo（WebSocket 必须显式透传 Upgrade）。
 * 后端地址可用环境变量覆盖，默认与 server.mjs 的默认后端端口对齐。
 */
const backend = process.env.BACKEND_URL || 'https://localhost:8080';
const centrifugo = process.env.CENTRIFUGO_WS_URL || 'http://localhost:8000';
const https = process.env.VITE_HTTPS === 'true'
    ? { pfx: readFileSync(fileURLToPath(new URL('../business-svc/src/main/resources/keystore.p12', import.meta.url))), passphrase: process.env.TLS_PFX_PASSWORD || 'password' }
    : undefined;

export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
    },
    server: {
        host: '0.0.0.0',
        port: Number(process.env.PORT || 5174),
        https,
        // 视频控件由 VITE_VIDEO_WIDGET_BASE 以独立 UMD 制品提供，坐席端不读取其源码或 dist。
        proxy: {
            '/api': { target: backend, changeOrigin: true, ws: false, secure: false },
            '/records': { target: backend, changeOrigin: true, secure: false },
            '/centrifugo': { target: centrifugo, changeOrigin: true, ws: true, rewrite: path => path.replace(/^\/centrifugo/, '') }
        }
    },
    build: { outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 1500 }
});
