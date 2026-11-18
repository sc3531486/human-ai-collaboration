import { test } from 'node:test';
import assert from 'node:assert/strict';
import config from '../vite.config.js';

test('客户入房信令通过渠道同源代理到达 Centrifugo WebSocket 路径', () => {
    const proxy = config.server.proxy['/centrifugo'];
    assert.equal(proxy.ws, true);
    assert.equal(proxy.rewrite('/centrifugo/connection/websocket'), '/connection/websocket');
    assert.equal(proxy.rewrite('/centrifugo/connection/websocket?format=json'), '/connection/websocket?format=json');
});
