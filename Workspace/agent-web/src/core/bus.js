/* 内核事件总线：替代 navis-web 里挂在 window 上的 CustomEvent 派发。 */

const channels = new Map();

function handlers(type) {
    if (!channels.has(type)) channels.set(type, new Set());
    return channels.get(type);
}

/**
 * 错误统一出口：坐席端任何"不再向上抛"的错误都必须经这里落到三个面——
 *  1. console.error（排障现场，含错误对象与调用域）；
 *  2. `system:error` 事件（监控/告警落点：运维可订阅该事件或在浏览器端采集上报）；
 *  3. `ui:toast` 错误提示（坐席肉眼可见，格式「[错误码] 人话原因」）。
 *
 * sticky：轮询类后台任务用 { sticky: true }——同一 scope + 同一原因只提示一次，
 * 避免 2.5s/3s/15s 定时器在持续故障时刷屏；console 与 system:error 仍逐次上报。
 */
let reporting = false;
const stickyShown = new Set();

export function report(scope, error, { sticky = false } = {}) {
    const err = error instanceof Error ? error : new Error(error == null ? String(error) : error.message || String(error));
    const code = err.code || (err.status ? `HTTP_${err.status}` : 'APP_ERROR');
    console.error(`[report] ${scope} [${code}]`, err);
    if (reporting) return;
    reporting = true;
    try {
        bus.emit('system:error', { scope, code, message: err.message, at: Date.now() });
        const key = `${scope}|${code}|${err.message}`;
        if (sticky && stickyShown.has(key)) return;
        if (sticky) stickyShown.add(key);
        bus.emit('ui:toast', { message: `[${code}] ${err.message}`, error: true });
    } finally {
        reporting = false;
    }
}

export const bus = {
    /**
     * 订阅内核事件，返回取消订阅的函数。
     * 事件名沿用 navis-web 的命名（bank:submit-start / agent:state / agent:connection …），
     * 便于按源码对照行为。
     */
    on(type, handler) {
        handlers(type).add(handler);
        return () => this.off(type, handler);
    },
    once(type, handler) {
        const off = this.on(type, detail => { off(); handler(detail); });
        return off;
    },
    off(type, handler) {
        const set = channels.get(type);
        if (set) set.delete(handler);
    },
    emit(type, detail) {
        // 复制一份快照：监听器内部可能增删订阅，直接遍历原集合会让迭代行为不可预期。
        // 监听器异常不允许只留在 console：经 report 上报到 system:error 与错误 toast。
        Array.from(handlers(type)).forEach(handler => {
            try { handler(detail); }
            catch (error) {
                console.error(`[bus] ${type} 监听器异常`, error);
                if (!reporting) report(`事件监听器 ${type}`, error);
            }
        });
        Array.from(channels.get('*') || []).forEach(handler => {
            try { handler({ type, detail }); }
            catch (error) {
                console.error('[bus] 通配监听器异常', error);
                if (!reporting) report('事件监听器 *', error);
            }
        });
    }
};

export default bus;
