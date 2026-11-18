import test from 'node:test';
import assert from 'node:assert/strict';
import { createChat } from '../src/app/chat.js';

test('人工接入后消息走信令转发接口，模型接口不被调用；推送与补拉去重', async () => {
    const oldDocument = globalThis.document;
    const oldFetch = globalThis.fetch;
    const messages = [];
    const calls = [];
    globalThis.document = { createElement: () => ({}) };
    globalThis.fetch = async (url, init) => {
        calls.push({ url, body: JSON.parse(init.body) });
        return { ok: true, json: async () => ({ success: true }) };
    };
    try {
        const refs = { input: { value: '请核实收款人' }, chat: { appendChild: node => messages.push(node), scrollHeight: 0 } };
        const chat = createChat(refs, { getConsent: () => true, getChatMode: () => 'HUMAN',
            getProcessId: () => 'process-1', getPeerId: () => 'cust-session-1' });
        await chat.sendMsg();
        assert.equal(calls.length, 1);
        assert.equal(calls[0].url, '/api/evidence/chat');
        assert.equal(calls[0].body.actorRole, 'CUSTOMER');
        assert.equal(calls[0].body.text, '请核实收款人');
        assert.equal(refs.input.value, '');
        const incoming = { eventId: 'agent-1', eventType: 'CHAT_AGENT_MESSAGE', payload: { text: '正在核实' } };
        chat.receiveMessage(incoming);
        chat.receiveMessage({ ...incoming, id: 15 });
        assert.equal(messages.filter(node => node.textContent === '正在核实').length, 1);
        chat.dispose();
    } finally { globalThis.fetch = oldFetch; globalThis.document = oldDocument; }
});

test('人工排队期间保留客户输入，既不调用模型也不发给尚未接听的坐席', async () => {
    const oldDocument = globalThis.document;
    const oldFetch = globalThis.fetch;
    globalThis.document = { createElement: () => ({}) };
    let called = false;
    globalThis.fetch = async () => { called = true; throw new Error('不应请求'); };
    try {
        const refs = { input: { value: '你好' }, chat: { appendChild() {}, scrollHeight: 0 } };
        const chat = createChat(refs, { getConsent: () => true, getChatMode: () => 'WAITING' });
        await chat.sendMsg();
        assert.equal(called, false);
        assert.equal(refs.input.value, '你好');
        chat.dispose();
    } finally { globalThis.fetch = oldFetch; globalThis.document = oldDocument; }
});
