/**
 * 坐席心跳的上报循环跑在 Worker 里，主线程只负责启停。
 *
 * 为什么非得用 Worker：浏览器会节流隐藏标签页里主线程的定时器（Chrome 在页面隐藏
 * 5 分钟后降到每分钟 1 次）。坐席把工作台切到后台查资料是常态，而服务端的掉线判据
 * 是「45 秒没有心跳」—— 用主线程定时器上报，后台待够 5 分钟就会被判成掉线，
 * 而人还在岗：名下工单被回收、坐席被置为离线。Worker 不受该节流策略约束。
 */

let timer = null;
let agentId = '';
let csrfToken = '';
let interval = 15000;

function schedule(next) {
    if (next > 0) interval = next;
    if (timer) clearInterval(timer);
    timer = agentId ? setInterval(beat, interval) : null;
}

async function beat() {
    if (!agentId) return;
    try {
        const response = await fetch('/api/agent/heartbeat', {
            method: 'POST',
            headers: { 'content-type': 'application/json', 'X-CSRF-Token': csrfToken },
            body: JSON.stringify({ agentId })
        });
        // 响应体必须是合法 JSON：解析失败按本拍失败处理，不许用 {} 把"服务端已挂"
        // 伪装成"这拍成功"——那样掉线时坐席端毫无提示。
        const result = await response.json();
        // 周期由服务端下发：它与超时阈值是一对必须配套的参数，两边各写一个常量必然漂移。
        if (result.intervalMs > 0 && result.intervalMs !== interval) schedule(result.intervalMs);
        if (response.ok) postMessage({ ok: true, at: Date.now(), via: 'worker' });
        else postMessage({ ok: false, at: Date.now(), via: 'worker', error: `HTTP ${response.status}` });
    } catch (error) {
        // 失败回传主线程，由主线程经 report 如实上报（每拍仍自然重试）。
        postMessage({ ok: false, at: Date.now(), via: 'worker', error: error?.message || '心跳上报失败' });
    }
}

self.onmessage = event => {
    const data = event.data || {};
    if (data.command === 'start') {
        agentId = data.agentId || '';
        csrfToken = data.csrfToken || '';
        schedule(data.intervalMs);
        // 启动时立刻报一次：服务端的超时扫描只对"有过心跳记录"的坐席生效，
        // 早一拍登记活性，就少一个签入后到首拍之间的判定空窗。
        beat();
    } else if (data.command === 'stop') {
        agentId = '';
        csrfToken = '';
        if (timer) clearInterval(timer);
        timer = null;
    }
};
