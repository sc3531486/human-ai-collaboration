import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import App from './App.vue';
import { report } from '@/core/bus.js';

/* 视觉沿用 navis-web 现有 CSS：类名与配色不改动，Element Plus 仅作交互基座。 */
import 'element-plus/dist/index.css';
import './styles/agent_workspace.css';
import './styles/agent_layout.css';
import './styles/agent_nodes.css';
import './styles/agent_reference.css';
import './styles/service-experience.css';

const app = createApp(App);

/* 错误必须有最终落点：组件事件处理器/异步回调里没有 catch 的错误，
 * 由全局 errorHandler 呈现为「[错误码] 人话原因」toast 并上报 system:error，
 * 不允许静默吞掉（契约 §3.1：错误抛给调用方，由调用方负责呈现）。 */
app.config.errorHandler = (error, _instance, info) => {
    console.error(`[vue] 渲染/事件处理异常（${info}）`, error);
    report(info ? `界面事件处理（${info}）` : '界面渲染', error);
};

window.addEventListener('unhandledrejection', event => {
    report('未处理的异步错误', event.reason instanceof Error ? event.reason : new Error(String(event.reason?.message || event.reason)));
});

app.use(createPinia()).use(ElementPlus, { locale: zhCn }).mount('#app');
