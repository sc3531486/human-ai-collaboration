import test from 'node:test';
import assert from 'node:assert/strict';
import { customerSignal } from '../src/core/realtime/centrifugo.js';

test('已订阅客户频道能接收签名节点 NODE_STATE，而非只消费入房及聊天消息', async () => {
    const previousSocket = globalThis.WebSocket;
    const previousLocation = globalThis.location;
    let socket;
    class Socket {
        constructor() { socket = this; queueMicrotask(() => this.onopen()); }
        send(frame) {
            const request = JSON.parse(frame);
            if (request.connect) queueMicrotask(() => this.deliver({ id: 1, connect: {} }));
            if (request.subscribe) queueMicrotask(() => this.deliver({ id: 2, subscribe: {} }));
        }
        deliver(message) { this.onmessage({ data: JSON.stringify(message) }); }
        close() { this.onclose?.(); }
    }
    globalThis.WebSocket = Socket;
    globalThis.location = { protocol: 'https:', host: 'channel.test' };
    let received;
    try {
        customerSignal.start({ id: 'customer-1', granted: { token: 'test-token', channel: 'customer:customer-1' },
            onNodeState: state => { received = state; } });
        await customerSignal.ready();
        socket.deliver({ push: { pub: { data: { type: 'NODE_STATE', processId: 'p1', nodeId: 'customer_sign',
            sequence: 2, nodePolicy: { actors: ['CUSTOMER'], fields: [{ name: 'signature_image', type: 'signature' }] } } } } });
        assert.equal(received.nodeId, 'customer_sign');
        assert.equal(received.nodePolicy.fields[0].type, 'signature');
    } finally {
        customerSignal.stop(); globalThis.WebSocket = previousSocket; globalThis.location = previousLocation;
    }
});
