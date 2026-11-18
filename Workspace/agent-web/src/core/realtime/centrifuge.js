/**
 * 坐席实时通道：Centrifugo 长连接接收振铃与队列事件，断线自动重连并由 HTTP 轮询兜底。
 *
 * 移植自 `navis-web/public/js/agent_realtime.js`，逐条保留带业务原因的边界：
 *  - **签入状态只能经 HTTP 回写**（/api/platform/agent/{id}/status）：Centrifugo 只承载下行推送，
 *    不承担签入语义；订阅就绪后才回写状态，避免振铃推送早于坐席可接收而丢失。
 *  - 连接地址固定走同源反代（/centrifugo/connection/websocket），避免 https 页面混合内容。
 *  - 令牌过期按同一契约换新令牌（getToken），长连接不会被静默降级为轮询。
 *  - 断线起 3s 轮询补齐状态；令牌/订阅不可自愈时重建客户端。
 *
 * 心跳**不在这里**：`setStatus('OFFLINE')` 会连带停心跳，见 media/heartbeat.js——
 * 签出即停，否则服务端会把主动签出再判一次掉线。
 */
import { request } from '../sdk.js';
import { identity } from '../identity.js';
import { bus, report } from '../bus.js';
import { startHeartbeat, stopHeartbeat } from '../media/heartbeat.js';
import { Centrifuge as CentrifugeClient } from 'centrifuge';

const TOKEN_API = '/api/centrifugo/token';
const SOCKET_PATH = '/centrifugo/connection/websocket';
const CALL_SNAPSHOT_API = id => `/api/platform/agent/${encodeURIComponent(id)}/calls`;

const handlers = new Map();

let client = null;
let subscription = null;
let agentId = '';
let channel = '';
let status = 'OFFLINE';
let connected = false;
let retry = 0;
let pollTimer = null;
let retryTimer = null;
let closedByUser = false;
let missingLibReported = false;
let subscribedReady = false;
let mediaGrant = null;

const emit = (type, detail) => {
    // 监听器异常不允许只留 console：经 report 上报（sticky 防事件突发刷屏）。
    (handlers.get(type) || []).forEach(handler => {
        try { handler(detail); }
        catch (error) { report(`实时事件监听 ${type}`, error, { sticky: true }); }
    });
    (handlers.get('*') || []).forEach(handler => {
        try { handler({ type, detail }); }
        catch (error) { report('实时事件监听 *', error, { sticky: true }); }
    });
    bus.emit('agent:realtime', { type, detail });
};

/** 连接地址固定走同源反代，避免 https 页面下的混合内容；服务端下发同源地址时沿用。 */
function endpoint(serverUrl) {
    try {
        const candidate = new URL(serverUrl || SOCKET_PATH, window.location.href);
        if (candidate.host === window.location.host) return candidate.toString();
    } catch (error) {
        // 未下发地址＝直接用契约默认路径（不是失败）；下发了却解析不了＝后端配置漂移，如实上报。
        if (serverUrl) report('实时通道地址解析', new Error(`服务端下发的连接地址不可用（${serverUrl}），改走同源反代路径`), { sticky: true });
    }
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.host}${SOCKET_PATH}`;
}

/** 令牌与频道由后端按同一契约下发，前端不硬编码密钥与地址。 */
async function authorize() {
    const result = mediaGrant || await request(TOKEN_API, { agentId });
    mediaGrant = null;
    if (!result.token) throw new Error('实时通道令牌签发失败');
    channel = result.channel || `agent:${agentId}`;
    return result;
}

/** 断线时用同一语义的 HTTP 快照补齐状态，避免坐席漏接来电。 */
async function snapshot() {
    const result = await request(CALL_SNAPSHOT_API(agentId));
    // queue 是该接口的确定字段（后端 success 时必带）：缺失即契约漂移，如实抛出。
    if (!result.queue) throw new Error('来电快照接口未返回 queue 字段');
    emit('QUEUE_SYNC', result.queue);
    if (result.queue.ringing) emit('CALL_RINGING', { ticket: result.queue.ringing, degraded: true });
    return result;
}

function startPolling() {
    if (pollTimer) return;
    // 失败继续下一拍重试是设计（瞬时抖动自愈），但失败本身必须可见（sticky 防刷屏）。
    pollTimer = setInterval(() => { snapshot().catch(error => report('来电快照轮询', error, { sticky: true })); }, 3000);
}
function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

function scheduleReconnect() {
    if (closedByUser || retryTimer) return;
    const delay = Math.min(8000, 1000 * Math.pow(2, retry++));
    retryTimer = setTimeout(() => { retryTimer = null; boot(); }, delay);
}

/** 事件信封 {domain,type,at,ticket} 统一在这里落到既有 emit(type, detail) 分支。 */
function dispatch(data) {
    let payload = data;
    if (typeof payload === 'string') {
        try { payload = JSON.parse(payload); }
        catch (error) {
            report('实时推送帧解析', new Error(`推送内容不是合法 JSON：${String(data).slice(0, 120)}`), { sticky: true });
            return;
        }
    }
    if (!payload || typeof payload !== 'object' || !payload.type) {
        report('实时推送帧校验', new Error(`推送帧缺少 type 字段：${JSON.stringify(payload)?.slice(0, 120)}`), { sticky: true });
        return;
    }
    if (payload.type === 'QUEUE_SYNC') {
        // queue 字段缺失要上报（契约漂移），但刷新动作仍要发生：刷新走 HTTP 全量拉取，
        // 正好用真实数据替换这一帧的缺口。
        if (!payload.queue) report('实时推送帧校验', new Error('QUEUE_SYNC 帧缺少 queue 字段'), { sticky: true });
        emit('QUEUE_SYNC', payload.queue || {});
    }
    else emit(payload.type, payload);
}

/**
 * 上线状态只能经 HTTP 回写：Centrifugo 只承担下行推送，不承载坐席签入语义。
 * 这是 §3.5 的红线——把签入写进 socket 会让「频道存活」与「坐席在岗」两个事实混淆。
 */
function publishStatus(next) {
    if (!agentId) return Promise.reject(new Error('签入状态回写缺少 agentId（坐席身份未确立）'));
    return request(`/api/platform/agent/${encodeURIComponent(agentId)}/status`, {
        name: identity.agentName, peerId: identity.peerId, skills: identity.agentSkills.join(','), status: next
    }).then(result => {
        // queue 同样是该接口的确定字段：缺失如实上报，但仍把状态回写成功的事实交给调用方。
        if (!result.queue) report('签入状态回写', new Error('状态接口未返回 queue 字段'), { sticky: true });
        emit('QUEUE_SYNC', result.queue || {});
        emit('AGENT_READY', result);
        return result;
    });
}

function teardown() {
    // 清理失败意味着旧连接可能残留（僵尸 socket/订阅），必须可见；不阻断新客户端创建。
    try { subscription?.unsubscribe(); }
    catch (error) { report('实时订阅退订', error, { sticky: true }); }
    try { client?.disconnect(); }
    catch (error) { report('实时连接断开', error, { sticky: true }); }
    subscription = null;
    client = null;
    connected = false;
    subscribedReady = false;
}

async function boot() {
    if (!agentId || closedByUser || client) return;
    const Centrifuge = CentrifugeClient;
    if (!Centrifuge) {
        // 实时推送库未加载（契约未规定此路径）：状态源只能是 3s HTTP 快照轮询。
        // 这是可感知的降级而非静默吞掉——上报一次 + 每拍轮询失败都会继续上报。
        if (!missingLibReported) {
            missingLibReported = true;
            report('实时推送通道', new Error(`实时推送库 window.${'Centrifuge'} 未加载，队列/振铃改用 HTTP 快照轮询获取`), { sticky: true });
        }
        startPolling();
        return;
    }
    try {
        const granted = await authorize();
        if (closedByUser || !agentId || client) return;
        const instance = new Centrifuge(endpoint(granted.url), {
            token: granted.token,
            // 令牌过期时按同一契约换新令牌，长连接不会被静默降级为轮询。
            getToken: () => authorize().then(result => result.token)
        });
        client = instance;
        subscription = instance.newSubscription(channel);
        subscription.on('publication', context => dispatch(context.data));
        // 订阅就绪后再回写状态，避免振铃推送早于坐席可接收而丢失。
        subscription.on('subscribed', () => {
            subscribedReady = true;
            retry = 0;
            publishStatus(status).catch(error => report('订阅就绪后状态回写', error, { sticky: true }));
        });
        subscription.subscribe();
        instance.on('connected', () => {
            connected = true;
            retry = 0;
            stopPolling();
            emit('CONNECTED', { agentId });
        });
        instance.on('disconnected', context => {
            connected = false;
            subscribedReady = false;
            emit('DISCONNECTED', { reason: context?.reason || 'transport' });
            startPolling();
        });
        instance.on('error', context => {
            // 令牌或订阅失效无法自愈时重建客户端，期间由轮询补齐状态。
            const error = context?.error || {};
            if (context?.type !== 'connection') return;
            if (error.code === 109 || /token|unauthorized/i.test(String(error.message || ''))) {
                teardown(); startPolling(); scheduleReconnect();
            }
        });
        instance.connect();
    } catch (error) {
        // 建链失败：错误如实上报（sticky 防重连风暴刷屏），轮询补齐 + 指数退避重建照常执行。
        report('实时通道建立', error, { sticky: true });
        startPolling();
        scheduleReconnect();
    }
}

export const realtime = {
    async ready(granted) {
        if (connected && subscribedReady) return;
        mediaGrant = granted || null;
        await boot();
        const started = Date.now();
        while (!closedByUser && !(connected && subscribedReady) && Date.now() - started < 12000) {
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        if (!(connected && subscribedReady)) throw new Error('坐席信令订阅未就绪，暂不能通知客户入房');
    },
    connect({ agentId: id, name, peerId, skills } = {}) {
        agentId = id || identity.agentId;
        if (name) identity.agentName = name;
        if (skills) identity.agentSkills = skills;
        peerId = peerId || identity.peerId;
        status = 'OFFLINE';
        closedByUser = false;
        boot();
        // 心跳随身份确立启动。只有真正在岗的坐席上报：签出态仍上报的话，
        // 服务端的超时扫描会把已经主动签出的坐席再判一次掉线。
        stopHeartbeat();
        return this;
    },
    on(type, handler) {
        if (!handlers.has(type)) handlers.set(type, []);
        handlers.get(type).push(handler);
        return this;
    },
    setStatus(next) {
        const previous = status;
        try {
            status = next;
            // 心率与在岗状态同源：签出即停（§3.5），避免服务端把主动签出当成掉线再处理一次。
            if (next === 'OFFLINE') stopHeartbeat();
            else startHeartbeat(agentId);
        } catch (error) {
            // 心跳启停失败（身份缺失/Worker 起不来）同样要回滚本地状态：不许留下
            // "本地已切换、服务端与心跳都还是旧状态"的三截断。
            status = previous;
            throw error;
        }
        // HTTP 回写失败时把本地状态与心跳回滚到上一状态并照常抛错：
        // 界面显示"已签入"而服务端没收到（或反之）会误导坐席继续等单。
        return publishStatus(next).catch(error => {
            status = previous;
            try {
                if (previous === 'OFFLINE') stopHeartbeat();
                else startHeartbeat(agentId);
            } catch (rollbackError) {
                report('状态回写失败后的心跳恢复', rollbackError, { sticky: true });
            }
            throw error;
        });
    },
    sync() { return snapshot(); },
    get connected() { return connected; },
    disconnect() {
        closedByUser = true;
        stopPolling();
        clearTimeout(retryTimer);
        retryTimer = null;
        teardown();
        status = 'OFFLINE';
        stopHeartbeat();
        // 关闭期的状态回写失败必须留痕（system:error + console）；页面即将卸载，
        // toast 未必来得及呈现，故此处只上报不依赖提示。
        publishStatus(status).catch(error => report('断开时状态回写', error, { sticky: true }));
        emit('DISCONNECTED', { reason: 'closed' });
    }
};

export default realtime;
