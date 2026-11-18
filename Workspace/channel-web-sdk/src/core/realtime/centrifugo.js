/*
 * 客户视频房间信令：只消费服务端指定的 VIDEO_ROOM_READY。
 *
 * 这里使用 Centrifugo JSON WebSocket 协议的极小子集，避免渠道 SDK 依赖 CDN
 * 或把坐席端的完整实时库打进来。流程状态仍走 STOMP；两条通道职责不混用。
 */
import { request } from '../sdk.js';

let socket = null;
let stopped = true;
let retryTimer = null;
let customerId = '';
let callback = null;
let retry = 0;
let initialGrant = null;
let subscribed = false;
let chatCallback = null;
let nodeCallback = null;
let endedCallback = null;
let membersCallback = null;

function endpoint() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${location.host}/centrifugo/connection/websocket`;
}

function scheduleReconnect() {
    if (stopped || retryTimer) return;
    const delay = Math.min(8000, 1000 * Math.pow(2, retry++));
    retryTimer = setTimeout(() => {
        retryTimer = null;
        connect();
    }, delay);
}

function publication(message) {
    const data = message?.push?.pub?.data || message?.pub?.data || message?.publication?.data;
    const handler = data?.type === 'VIDEO_ROOM_READY' ? callback
        : data?.type === 'CHAT_MESSAGE' ? chatCallback : data?.type === 'NODE_STATE' ? nodeCallback : data?.type === 'CALL_ENDED' ? endedCallback : data?.type === 'ROOM_PARTICIPANTS_CHANGED' ? membersCallback : null;
    if (typeof handler !== 'function') return;
    Promise.resolve(handler(data)).catch(error => console.error('[customer-centrifugo] 消息处理失败', error));
}

async function connect() {
    if (stopped || socket || !customerId) return;
    try {
        const granted = initialGrant || await request('/api/centrifugo/token', { customerId });
        initialGrant = null;
        if (stopped || socket || !granted.token || !granted.channel) return;
        const instance = new WebSocket(endpoint());
        socket = instance;
        instance.onopen = () => {
            if (socket === instance) instance.send(JSON.stringify({ id: 1, connect: { token: granted.token } }));
        };
        instance.onmessage = (event) => {
            for (const line of String(event.data).split('\n').filter(Boolean)) {
            let message;
            try { message = JSON.parse(line); } catch { continue; }
            if (Object.keys(message).length === 0) { instance.send('{}'); continue; }
            if (message.error) { console.error('[customer-centrifugo] 信令命令失败', message.error); instance.close(); continue; }
            if (message.connect && socket === instance) {
                retry = 0;
                instance.send(JSON.stringify({ id: 2, subscribe: { channel: granted.channel } }));
            }
            if (message.id === 2 && message.subscribe) subscribed = true;
            publication(message);
            }
        };
        instance.onerror = () => instance.close();
        instance.onclose = () => {
            if (socket !== instance) return;
            socket = null;
            subscribed = false;
            scheduleReconnect();
        };
    } catch (error) {
        console.warn('[customer-centrifugo] 客户视频信令连接失败，将重试：', error);
        scheduleReconnect();
    }
}

export const customerSignal = {
    async ready(timeout = 12000) {
        const started = Date.now();
        while (!subscribed && !stopped && Date.now() - started < timeout) {
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        if (!subscribed) throw new Error('客户入房信令订阅尚未就绪，请重试');
    },
    start({ id, granted, onVideoRoomReady, onChatMessage, onNodeState, onCallEnded, onParticipantsChanged }) {
        this.stop();
        customerId = id || '';
        initialGrant = granted || null;
        callback = onVideoRoomReady || null;
        chatCallback = onChatMessage || null;
        nodeCallback = onNodeState || null;
        endedCallback = onCallEnded || null;
        membersCallback = onParticipantsChanged || null;
        stopped = false;
        retry = 0;
        connect();
    },
    stop() {
        stopped = true;
        subscribed = false;
        retry = 0;
        clearTimeout(retryTimer);
        retryTimer = null;
        const previous = socket;
        socket = null;
        if (previous) previous.close();
        customerId = '';
        callback = null;
        chatCallback = null;
        nodeCallback = null;
        endedCallback = null;
        membersCallback = null;
        initialGrant = null;
    },
};

export default customerSignal;
