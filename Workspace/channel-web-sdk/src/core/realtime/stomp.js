/*
 * 客户个人消息通道：/ws 上的 STOMP 下行推送（源：mobile-realtime.js）。
 * HTTP 仅用于取令牌、首次校准与断线兜底；业务消息只认 NODE_STATE。
 *
 * 帧解析沿用原生实现的口径：以 \0 分帧，CONNECT 后订阅 /user/queue/events，
 * 心跳 10s，退避重连 min(8s, 1s * 2^n)。
 *
 * 渠道令牌（契约 §6.3-2）：/api/message/token 扩展携带 channelId / tenantId，
 * 渠道方不直连后端的其余契约。
 */
import { request } from '../sdk.js';

let socket = null;
let retryTimer = null;
let heartbeat = null;
let connected = false;
let closed = false;
let identityValue = '';
let buffer = '';
let generation = 0;
let retry = 0;
let options = { customerId: '' };
let channelContext = {};

/** 渠道方在 mount 配置的 channelId / tenantId，取令牌时一并上报。 */
export function setChannelContext(context = {}) {
    channelContext = {
        channelId: context.channelId || '',
        tenantId: context.tenantId || '',
    };
}

function notify(status) {
    connected = status === 'live';
    if (options.onConnection) options.onConnection(status);
}

function escapeHeader(value) {
    return String(value).replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/:/g, '\\c');
}

function send(command, headers = {}, body = '') {
    if (!socket || socket.readyState !== 1) return;
    const head = [command];
    Object.keys(headers).forEach((key) => {
        head.push(`${key}:${escapeHeader(headers[key])}`);
    });
    head.push('', body);
    socket.send(head.join('\n') + '\0');
}

function retryLater() {
    if (closed || retryTimer) return;
    retryTimer = setTimeout(() => {
        retryTimer = null;
        boot();
    }, Math.min(8000, 1000 * Math.pow(2, retry++)));
}

function receive(value) {
    buffer += typeof value === 'string' ? value : '';
    let end = buffer.indexOf('\0');
    while (end >= 0) {
        const frame = buffer.slice(0, end).replace(/^\n+/, '');
        buffer = buffer.slice(end + 1);
        const command = frame.split('\n')[0];
        if (command === 'CONNECTED') {
            send('SUBSCRIBE', { id: 'mobile-events', destination: '/user/queue/events', ack: 'auto' });
            retry = 0;
            notify('live');
            if (heartbeat) clearInterval(heartbeat);
            heartbeat = setInterval(() => {
                if (socket && socket.readyState === 1) socket.send('\n');
            }, 10000);
            if (options.onReconnect) options.onReconnect();
        } else if (command === 'MESSAGE') {
            try {
                const message = JSON.parse(frame.slice(frame.indexOf('\n\n') + 2));
                // 只执行业务消息；其余（订阅回执、心跳）静默忽略。
                if (message.type === 'NODE_STATE' && options.onState) options.onState(message);
            } catch {
                /* 非业务消息不处理 */
            }
        } else if (command === 'ERROR' && socket) {
            socket.close();
        }
        end = buffer.indexOf('\0');
    }
}

async function boot() {
    if (closed || socket || !identityValue) return;
    const expected = generation;
    try {
        const granted = await request('/api/message/token', { customerId: identityValue, ...channelContext });
        // 帧丢失保护：generation 变化说明通道已被重建，旧令牌不该再建链。
        if (expected !== generation || closed || socket) return;
        if (!granted.token) throw new Error('消息认证未完成');
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const instance = new WebSocket(`${protocol}//${location.host}/ws`);
        socket = instance;
        buffer = '';
        instance.onopen = () => {
            if (socket === instance) {
                send('CONNECT', {
                    'accept-version': '1.2',
                    'heart-beat': '10000,10000',
                    authorization: `Bearer ${granted.token}`,
                    host: location.host,
                });
            }
        };
        instance.onmessage = (event) => {
            if (socket === instance) receive(event.data);
        };
        instance.onerror = () => instance.close();
        instance.onclose = () => {
            if (socket !== instance) return;
            socket = null;
            if (heartbeat) clearInterval(heartbeat);
            notify('polling');
            retryLater();
        };
    } catch {
        if (expected !== generation) return;
        notify('polling');
        retryLater();
    }
}

export function connect(config) {
    disconnect();
    options = config;
    identityValue = config.customerId || '';
    closed = false;
    retry = 0;
    notify('connecting');
    boot();
}

export function disconnect() {
    closed = true;
    generation += 1;
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = null;
    if (heartbeat) clearInterval(heartbeat);
    const previous = socket;
    socket = null;
    if (previous) previous.close();
    connected = false;
}

export const stomp = {
    connect,
    disconnect,
    setChannelContext,
    get connected() {
        return connected;
    },
};
export default stomp;
