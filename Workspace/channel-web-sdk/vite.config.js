/*
 * Vite 配置：库模式构建（ESM + UMD）+ 开发反向代理。
 *
 * 构建约束（契约 §8.2）：
 *   - 目标 ES2018+（esbuild 直接产出，不走 terser）
 *   - minify: esbuild（CSP 禁 unsafe-eval → 全部编译期完成，无运行时模板编译）
 *   - 三件产物：dist/vb-banking.esm.js、dist/vb-banking.umd.js、dist/vb-banking.css
 *   - UMD 全局名 window.VideoBanking（lead 裁定「UMD 全局名唯一性」；控件为 VideoBankingWidget）
 *
 * 开发代理与 navis-web/server.mjs、nginx.conf.example 语义一致：
 *   /api、/records → business-svc（8080）；/ws → message-svc（8085，STOMP）；
 *   /centrifugo → Centrifugo（8000）。可用环境变量覆盖。
 */
import { defineConfig } from 'vite';

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const MESSAGE_WS_URL = process.env.MESSAGE_WS_URL || 'http://localhost:8085';
const CENTRIFUGO_WS_URL = process.env.CENTRIFUGO_WS_URL || 'http://localhost:8000';

export default defineConfig({
    build: {
        target: 'es2018',
        minify: 'esbuild',
        sourcemap: true,
        lib: {
            entry: 'src/index.js',
            formats: ['es', 'umd'],
            name: 'VideoBanking',
            cssFileName: 'vb-banking',
            fileName: (format) => (format === 'es' ? 'vb-banking.esm.js' : 'vb-banking.umd.js'),
        },
        rollupOptions: {
            // SDK 自包含：不外链任何第三方运行时（dependencies 为空）。
            external: [],
            output: {
                exports: 'named',
                // 动态导入（GenericNode / 场景页懒加载）全部内联：产物即三件，零外部 chunk。
                inlineDynamicImports: true,
                assetFileNames: 'vb-banking.[ext]',
            },
        },
    },
    server: {
        port: 5173,
        proxy: {
            '/api': { target: BACKEND_URL, changeOrigin: true },
            '/records': { target: BACKEND_URL, changeOrigin: true },
            '/ws': { target: MESSAGE_WS_URL, ws: true, changeOrigin: true },
            '/centrifugo': { target: CENTRIFUGO_WS_URL, ws: true, changeOrigin: true },
        },
    },
});
