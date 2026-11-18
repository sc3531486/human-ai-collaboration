/*
 * 开发调试入口（npm run dev）：在 #app 上挂载一个 SDK 实例。
 * 生产集成方只 import src/index.js（或打包产物），不使用本文件。
 */
import { mount } from './index.js';

const sdk = mount('#app', {
    apiBase: '',           // 同源，经 Vite 反向代理到 business-svc
    channelId: 'dev-channel',
    tenantId: 'default',
    // widgetUrl: '/vendor/vb-video.umd.js',  // 控件 UMD（联调时由 lead 通知后启用）
});

window.__vbSdk = sdk;
sdk.startBusiness().then(() => {
    console.log('[dev] 渠道 SDK 已启动（UMD 全局名预览: window.VideoBanking）');
}).catch((error) => {
    console.error('[dev] startBusiness 失败:', error);
});
