import assert from 'node:assert/strict';

// 本机联调：独立测试身份，不启动交易、不写入演示历史，不输出令牌。
const subject = 'media-probe-' + Date.now();
async function post(url, body) {
    const response = await fetch(url, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
        signal: AbortSignal.timeout(10000),
    });
    const result = await response.json();
    assert.equal(response.ok, true, `HTTP ${response.status}`);
    return result;
}
const user = await post('http://127.0.0.1:8090/api/media/users', { role: 'CUSTOMER', subject });
const second = await post('http://127.0.0.1:8090/api/media/users', { role: 'CUSTOMER', subject });
assert.notEqual(user.peerId, second.peerId, '两次申请必须获得不同的媒体用户');
assert.equal(user.channel, 'customer:' + subject);
assert.ok(user.token);
// 可传入渠道/坐席同源反代地址，必须同时验证浏览器实际经过的代理链路。
const endpoint = process.argv[2] || 'ws://127.0.0.1:8000/connection/websocket';
const socket = new WebSocket(endpoint);
try {
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('信令收取超时')), 12000);
        socket.onopen = () => socket.send(JSON.stringify({ id: 1, connect: { token: user.token } }));
        socket.onerror = () => { clearTimeout(timer); reject(new Error('WebSocket 连接失败')); };
        socket.onmessage = async event => {
            try {
                for (const line of String(event.data).split('\n').filter(Boolean)) {
                    const message = JSON.parse(line);
                    if (!Object.keys(message).length) { socket.send('{}'); continue; }
                    assert.equal(message.error, undefined, JSON.stringify(message.error));
                    if (message.connect) socket.send(JSON.stringify({ id: 2, subscribe: { channel: user.channel } }));
                    if (message.id === 2 && message.subscribe) {
                        const sent = await post(`http://127.0.0.1:8082/internal/customer/${subject}/publish`, {
                            type: 'VIDEO_ROOM_READY', processId: 'probe-only', roomId: 'probe-room', agentPeerId: second.peerId,
                        });
                        assert.equal(sent.delivered, true);
                    }
                    if (message.push?.pub?.data?.type === 'VIDEO_ROOM_READY') {
                        assert.equal(message.push.pub.data.agentPeerId, second.peerId);
                        clearTimeout(timer); resolve();
                    }
                }
            } catch (error) { clearTimeout(timer); reject(error); }
        };
    });
    console.log('通过：业务→media申请独立用户→信令订阅→收到VIDEO_ROOM_READY；未创建交易历史。');
} finally { socket.close(); }
