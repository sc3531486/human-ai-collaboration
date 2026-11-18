import assert from 'node:assert/strict';
import { customerSignal } from '../channel-web-sdk/src/core/realtime/centrifugo.js';

// 使用真正的渠道信令模块与同源代理验证签名节点；不启动流程、不产生交易历史。
const subject = 'node-probe-' + Date.now();
globalThis.location = new URL('https://127.0.0.1:5175/');
async function post(url, body) {
    const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body), signal: AbortSignal.timeout(10000) });
    assert.equal(response.ok, true, `HTTP ${response.status}`);
    return response.json();
}
const grant = await post('http://127.0.0.1:8090/api/media/users', { role: 'CUSTOMER', subject });
let finish;
let timeout;
const received = new Promise((resolve, reject) => {
    finish = resolve;
    timeout = setTimeout(() => reject(new Error('未收到签名节点推送')), 12000);
});
try {
    customerSignal.start({ id: subject, granted: grant, onNodeState: finish });
    await customerSignal.ready();
    const sent = await post(`http://127.0.0.1:8082/internal/customer/${subject}/publish`, {
        type: 'NODE_STATE', processId: 'isolated-probe', nodeId: 'customer_sign', sequence: 1,
        nodeName: '客户签名', nodePolicy: { actors: ['CUSTOMER'], fields: [{ type: 'signature', name: 'signature_image' }] },
    });
    assert.equal(sent.delivered, true);
    const state = await received;
    assert.equal(state.nodeId, 'customer_sign');
    assert.equal(state.nodePolicy.fields[0].type, 'signature');
    console.log('通过：真实渠道信令模块经 HTTPS 代理收到客户签名 NODE_STATE；未创建交易。');
} finally { clearTimeout(timeout); customerSignal.stop(); }
