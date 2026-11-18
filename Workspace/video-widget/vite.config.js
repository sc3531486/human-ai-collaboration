/**
 * vite lib 构建（契约 §8.1）：产出三件交付物
 *   dist/vb-video.esm.js  —— ESM
 *   dist/vb-video.umd.js  —— UMD，全局名 window.VideoBankingWidget
 *                            （§8.2 全局名唯一性：window.VideoBanking 归 channel-web-sdk 所有）
 *   dist/vb-video.css     —— 控件样式（src/styles.css，经 index.js 的 CSS import 抽出）
 *
 * 红线：target ES2018+，**不做 ES5 / IE11 降级**（§8.1）。
 */
import { defineConfig } from 'vite';

export default defineConfig({
    build: {
        target: 'es2018',
        sourcemap: false,
        emptyOutDir: true, // 清掉历史 tsc 产物（dist/*.d.ts 等）
        minify: false,     // 交付源码可读，便于宿主打断点排查；压缩交给宿主自己的打包器
        lib: {
            entry: 'src/index.js',
            formats: ['es', 'umd'],
            name: 'VideoBankingWidget', // UMD 全局（§8.2 全局名唯一性）
            fileName: format => (format === 'es' ? 'vb-video.esm.js' : 'vb-video.umd.js'),
            cssFileName: 'vb-video'
        },
        rollupOptions: {
            output: {
                // named + default 混出：UMD 下宿主统一走 VideoBankingWidget.mount / .video 等具名导出
                exports: 'named'
            }
        }
    }
});
