/**
 * 坐席在岗心跳：证明"这个坐席的应用还活着"。
 *
 * 与 Centrifugo 的 presence 归零是两条互补的判据：presence 只证明 socket 活着
 * （坐席端 JS 卡死时 socket 仍连着），心跳只证明应用活着（Centrifugo 挂掉时心跳仍能到达）。
 * 两条在服务端汇合到同一个掉线处理入口，这里只负责按周期上报。
 *
 * ★ 上报循环**必须跑在 Worker 里**（见 heartbeat.worker.js，契约 §3.5）：主线程定时器
 * 在隐藏标签页会被浏览器节流到分钟级（Chrome 隐藏 5 分钟后降到每分钟 1 次），而服务端的
 * 掉线判据是「45 秒没有心跳」——用主线程定时器会让在岗坐席被误判掉线。
 * 因此**不存在主线程兜底**：Worker 起不来就如实报错，由调用方决定签入成败，绝不悄悄换判据。
 *
 * ★ 签出即停：服务端会把主动签出再判一次掉线，因此 stop() 由 setStatus('OFFLINE') 与 disconnect() 触发。
 *
 * 失败面：startHeartbeat 同步抛错（身份缺失 / Worker 创建失败）；运行中 Worker 报错或
 * 心跳回执 ok:false 经 report 上报到 system:error 与错误 toast（sticky 防每拍刷屏）。
 */
const WORKER_PATH = new URL('./heartbeat.worker.js', import.meta.url);

let worker = null;

import { bus, report } from '../bus.js';
import { identity } from '../identity.js';

const notify = detail => bus.emit('agent:heartbeat', detail);

/** 幂等：签入与状态切换会反复调用，不能叠出多个上报循环。 */
export function startHeartbeat(agentId) {
    // 身份缺失不是"没心跳就行"的静默场景：签入链路必须因此失败。
    if (!agentId) throw new Error('心跳启动失败：坐席身份（agentId）未确立');
    if (!worker) {
        // 创建失败如实抛出——退回主线程定时器会把 §3.5 的判据悄悄换掉（后台被节流→误判掉线）。
        worker = new Worker(WORKER_PATH, { type: 'module' });
        worker.onmessage = event => {
            const data = event.data || {};
            notify(data);
            if (data.ok === false) {
                report('坐席心跳', new Error(`心跳上报失败${data.error ? `：${data.error}` : `（${data.via || 'worker'}）`}`), { sticky: true });
            }
        };
        // Worker 自身崩溃（脚本加载失败/运行异常）无法用 throw 传达（发生在异步上下文）：
        // 终止并上报，坐席会看到错误提示；不换主线程兜底（§3.5 红线）。
        worker.onerror = event => {
            const error = new Error(`心跳 Worker 异常：${event?.message || '脚本加载或运行失败'}`);
            try { worker?.terminate(); } catch (cleanup) { /* 已终止 */ }
            worker = null;
            report('坐席心跳 Worker', error, { sticky: true });
        };
    }
    worker.postMessage({ command: 'start', agentId, csrfToken: identity.csrfToken });
}

export function stopHeartbeat() {
    if (worker) worker.postMessage({ command: 'stop' });
}

export default { startHeartbeat, stopHeartbeat };
