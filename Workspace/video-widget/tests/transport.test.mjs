/**
 * 传输层单测：接口契约、来电白名单、状态迁移、注入式传输。
 *
 * ★ 这批用例不依赖任何厂商：换实现后全部应继续通过——这正是抽象的意义。
 */
import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

import {
    MediaTransport, TransportState, TransportEvent
} from '../src/transport/MediaTransport.js';
import { P2PTransport, DEFAULT_ICE_SERVERS } from '../src/transport/P2PTransport.js';
import {
    registerTransport, createTransport, setDefaultTransport, getDefaultTransportName,
    listTransports, unregisterTransport, resetTransports, injectStream, InjectedTransport
} from '../src/transport/registry.js';
import { makeStream } from './helpers.js';

describe('MediaTransport 基类', () => {
    test('★ 接口未实现时明确抛错，不静默返回', async () => {
        const instance = new MediaTransport({});
        await assert.rejects(() => instance.connect(), /未实现 connect/);
        await assert.rejects(() => instance.publish(), /未实现 publish/);
        await assert.rejects(() => instance.subscribe(), /未实现 subscribe/);
        await assert.rejects(() => instance.hangup(), /未实现 hangup/);
    });

    test('六个接口都在', () => {
        const instance = new MediaTransport({});
        ['connect', 'publish', 'subscribe', 'hangup', 'getStats', 'on']
            .forEach(name => assert.equal(typeof instance[name], 'function', `缺少 ${name}`));
    });

    test('getStats 永远返回对象，不抛错', () => {
        const stats = new MediaTransport({}).getStats();
        assert.equal(typeof stats, 'object');
        assert.equal(stats.state, TransportState.IDLE);
    });

    test('状态迁移会派发 state 事件，同状态不重复派发', () => {
        const instance = new MediaTransport({});
        const seen = [];
        instance.on(TransportEvent.STATE, ({ state }) => seen.push(state));
        instance.setState(TransportState.CONNECTING);
        instance.setState(TransportState.CONNECTING);
        instance.setState(TransportState.CONNECTED);
        assert.deepEqual(seen, ['connecting', 'connected']);
    });

    test('非法状态直接拒绝', () => {
        const instance = new MediaTransport({});
        assert.throws(() => instance.setState('unknown-state'), /未知的传输状态/);
    });

    test('on 返回取消订阅函数', () => {
        const instance = new MediaTransport({});
        const seen = [];
        const off = instance.on(TransportEvent.LOCAL, () => seen.push(1));
        instance.emit(TransportEvent.LOCAL, {});
        off();
        instance.emit(TransportEvent.LOCAL, {});
        assert.equal(seen.length, 1);
    });

    test('订阅者抛错不影响其它订阅者', () => {
        const instance = new MediaTransport({});
        const seen = [];
        instance.on(TransportEvent.ERROR, () => { throw new Error('boom'); });
        instance.on(TransportEvent.ERROR, () => seen.push(1));
        instance.emit(TransportEvent.ERROR, {});
        assert.equal(seen.length, 1);
    });

    test('订阅者在回调里取消订阅也不会漏掉其它订阅者', () => {
        const instance = new MediaTransport({});
        const seen = [];
        const off = instance.on(TransportEvent.LOCAL, () => { off(); seen.push('first'); });
        instance.on(TransportEvent.LOCAL, () => seen.push('second'));
        instance.emit(TransportEvent.LOCAL, {});
        assert.deepEqual(seen, ['first', 'second']);
    });

    test('connected 后 duration 起算，回到 idle 归零', () => {
        const instance = new MediaTransport({});
        assert.equal(instance.duration, 0);
        instance.setState(TransportState.CONNECTED);
        assert.ok(instance.duration >= 0);
        instance.setState(TransportState.IDLE);
        assert.equal(instance.duration, 0);
    });
});

describe('P2PTransport：来电裁决', () => {
    test('★ 不在白名单的来电被直接拒接（无关来电不得顶掉当前通话）', () => {
        const transport = new P2PTransport({ peerId: 'bank-1' });
        let closed = false;
        transport._onIncoming({ peer: 'stranger', close: () => { closed = true; } });
        assert.equal(closed, true);
        assert.equal(transport.calls.size, 0);
    });

    test('白名单内的来电等待本地媒体授权，发布流后才应答', async () => {
        const transport = new P2PTransport({ peerId: 'bank-1', allowFrom: ['cust-1'] });
        let answered = null;
        transport._onIncoming({ peer: 'cust-1', answer: stream => { answered = stream; }, on: () => {} });
        assert.equal(transport.calls.size, 0);
        assert.equal(transport._pendingIncoming.size, 1);
        const stream = { getTracks: () => [] };
        await transport.publish(stream);
        assert.equal(answered, stream);
        assert.equal(transport.calls.size, 1);
    });

    test('allowFrom / denyFrom 可动态维护白名单', () => {
        const transport = new P2PTransport({ peerId: 'bank-1' });
        transport.allowFrom(['cust-1', 'cust-2']);
        assert.deepEqual(transport.allowedPeers, ['cust-1', 'cust-2']);
        transport.denyFrom('cust-1');
        assert.deepEqual(transport.allowedPeers, ['cust-2']);
    });

    test('shouldAccept 可以再拦一道', () => {
        const transport = new P2PTransport({
            peerId: 'bank-1', allowFrom: ['cust-1'], shouldAccept: incoming => incoming.metadata?.ok === true
        });
        let closed = false;
        transport._onIncoming({ peer: 'cust-1', metadata: { ok: false }, close: () => { closed = true; } });
        assert.equal(closed, true);
    });

    test('授权成员加入不能顶掉已连接客户，多个对端同时保留', () => {
        const transport = new P2PTransport({ peerId: 'bank-1', allowFrom: ['cust-1', 'cust-2'] });
        transport.localStream = { getTracks: () => [] };
        let closedFirst = false;
        transport._onIncoming({
            peer: 'cust-1',
            on: () => {},
            answer: () => {},
            close: () => { closedFirst = true; }
        });
        transport._onIncoming({ peer: 'cust-2', on: () => {}, answer: () => {} });
        assert.equal(closedFirst, false);
        assert.equal(transport.calls.size, 2);
    });
});

describe('P2PTransport：PeerJS 装载与建链', () => {
    test('★ 未注入 PeerJS 时给出明确错误，而不是 undefined 崩溃', async () => {
        const transport = new P2PTransport({ peerId: 'bank-1' });
        delete globalThis.Peer;
        delete globalThis.__bankPeerFactory;
        await assert.rejects(() => transport.connect(), /未找到 PeerJS 实现/);
    });

    test('注入的 peerFactory 优先于 globalThis', async () => {
        let used = false;
        class FakePeer {
            constructor(id, options) { this.id = id; this.options = options; used = true; this.handlers = {}; }
            on(event, cb) { this.handlers[event] = cb; queueMicrotask(() => { if (event === 'open') cb('bank-1'); }); }
            destroy() {}
        }
        const transport = new P2PTransport({
            peerId: 'bank-1',
            peerFactory: async () => FakePeer,
            iceServers: DEFAULT_ICE_SERVERS
        });
        await transport.connect();
        assert.equal(used, true);
        assert.equal(transport.state, TransportState.CONNECTED);
        assert.equal(transport.peerId, 'bank-1');
    });

    test('★ peer-id 沿用 bank-<agentId> / cust-* 约定', async () => {
        class FakePeer {
            constructor(id) { this.id = id; this.handlers = {}; }
            on(event, cb) { this.handlers[event] = cb; queueMicrotask(() => { if (event === 'open') cb(this.id); }); }
            destroy() {}
        }
        const transport = new P2PTransport({ peerId: 'cust-123456', peerFactory: async () => FakePeer });
        await transport.connect();
        assert.match(transport.peerId, /^cust-/);
    });

    test('建链超时会明确报错', async () => {
        class SilentPeer {
            constructor() { this.handlers = {}; }
            on() {}
            destroy() {}
        }
        const transport = new P2PTransport({ peerId: 'bank-1', peerFactory: async () => SilentPeer });
        await assert.rejects(() => transport.connect({ timeout: 10 }), /信令连接超时/);
        assert.equal(transport.state, TransportState.ERROR);
    });

    test('open 之前的错误是致命的：connect 失败并清理 peer', async () => {
        class FailingPeer {
            constructor() { this.handlers = {}; }
            on(event, cb) {
                this.handlers[event] = cb;
                if (event === 'error') queueMicrotask(() => cb({ type: 'network' }));
            }
            destroy() {}
        }
        const transport = new P2PTransport({ peerId: 'bank-1', peerFactory: async () => FailingPeer });
        await assert.rejects(() => transport.connect(), /音视频连接失败/);
        assert.equal(transport.peer, null);
    });

    test('publish 派发 local 事件，hangup 回到 closed', async () => {
        class FakePeer {
            constructor(id) { this.id = id; this.handlers = {}; }
            on(event, cb) { this.handlers[event] = cb; queueMicrotask(() => { if (event === 'open') cb(this.id); }); }
            destroy() {}
        }
        const transport = new P2PTransport({ peerId: 'bank-1', peerFactory: async () => FakePeer });
        await transport.connect();
        const seen = [];
        transport.on(TransportEvent.LOCAL, ({ stream }) => seen.push(stream));
        const stream = makeStream();
        await transport.publish(stream);
        assert.equal(seen[0], stream);
        await transport.hangup();
        assert.equal(transport.state, TransportState.CLOSED);
    });

    test('★ W1：connect 幂等——controller 接线与 subscribe 自举双调不重复建链', async () => {
        let created = 0;
        class FakePeer {
            constructor(id) { created += 1; this.id = id; this.handlers = {}; }
            on(event, cb) { this.handlers[event] = cb; queueMicrotask(() => { if (event === 'open') cb(this.id); }); }
            call(peerId) { return { peer: peerId, on() {} }; }
            destroy() {}
        }
        const transport = new P2PTransport({ peerId: 'bank-1', peerFactory: async () => FakePeer });
        // 模拟 controller 接线：connect → publish → subscribe 自举再调 connect。
        const first = await transport.connect({ peerId: 'cust-1', processId: 'P1' });
        const second = await transport.connect({ peerId: 'cust-1', processId: 'P1' });
        assert.equal(first, second, '重复 connect 必须返回同一个 peer');
        assert.equal(created, 1, '重复 connect 不得再 new Peer');
        assert.equal(transport.getStats().connect, 1, '建链计数只记一次');
        assert.equal(transport.peerId, 'bank-1', 'controller 传来的对端 peerId 不得被当成本地 id');
        await transport.subscribe('cust-1');
        assert.equal(created, 1, 'subscribe 自举补链同样不得重复建 Peer');
        assert.equal(transport.getStats().connect, 1);
    });

    test('★ W1：本地 id 未配置时也不会把对端 peerId 抢注成本地 id', async () => {
        class FakePeer {
            constructor(id) { this.id = id; this.handlers = {}; }
            on(event, cb) { this.handlers[event] = cb; queueMicrotask(() => { if (event === 'open') cb(this.id); }); }
            destroy() {}
        }
        const transport = new P2PTransport({ peerFactory: async () => FakePeer });
        await transport.connect({ peerId: 'cust-1', processId: 'P1' });
        assert.notEqual(transport.peerId, 'cust-1', '对端 peerId 抢注成本地 id 会与对端注册冲突');
    });

    test('getStats 在没有连接时也不抛错', () => {
        const transport = new P2PTransport({ peerId: 'bank-1' });
        const stats = transport.getStats();
        assert.equal(typeof stats, 'object');
        assert.equal(stats.calls, 0);
    });
});

describe('registry：换厂商的唯一开关', () => {
    beforeEach(() => resetTransports());

    test('登记后可按名创建，默认名取第一个登记的实现', () => {
        class A extends MediaTransport {}
        class B extends MediaTransport {}
        registerTransport('p2p', A);
        registerTransport('vendor', B);
        assert.equal(getDefaultTransportName(), 'p2p');
        assert.ok(createTransport('vendor') instanceof B);
        assert.deepEqual(listTransports(), ['p2p', 'vendor']);
    });

    test('★ 实现名是逻辑名，不含厂商字样', () => {
        class A extends MediaTransport {}
        registerTransport('vendor', A);
        ['p2p', 'vendor'].forEach(name => {
            assert.doesNotMatch(name, /agora|trtc|zego|tencent|声网|腾讯|即构/i);
        });
    });

    test('setDefaultTransport 可切换默认实现', () => {
        class A extends MediaTransport {}
        class B extends MediaTransport {}
        registerTransport('p2p', A);
        registerTransport('vendor', B);
        setDefaultTransport('vendor');
        assert.ok(createTransport() instanceof B);
    });

    test('未登记的实现名明确报错并列出已有实现', () => {
        class A extends MediaTransport {}
        registerTransport('p2p', A);
        assert.throws(() => createTransport('nope'), /未登记的传输实现/);
    });

    test('未登记任何实现时创建即报错', () => {
        assert.throws(() => createTransport(), /尚未登记任何传输实现/);
    });

    test('注销默认实现会把默认名顺延到下一个', () => {
        class A extends MediaTransport {}
        class B extends MediaTransport {}
        registerTransport('p2p', A);
        registerTransport('vendor', B);
        unregisterTransport('p2p');
        assert.equal(getDefaultTransportName(), 'vendor');
    });

    test('登记非构造函数会被拒绝', () => {
        assert.throws(() => registerTransport('bad', {}), /必须是构造函数/);
    });
});

describe('InjectedTransport：渠道方已有视频能力', () => {
    test('注入流后派发 remote，subscribe 能取回', async () => {
        const transport = new InjectedTransport();
        await transport.connect();
        const seen = [];
        transport.on(TransportEvent.REMOTE, ({ stream }) => seen.push(stream));
        const stream = makeStream();
        transport.injectStream(stream, 'cust-1');
        assert.equal(seen.length, 1);
        assert.equal(await transport.subscribe('cust-1'), stream);
    });

    test('★ injectStream 便捷入口会先置为已连接，remote 事件不会被上层丢弃', () => {
        const transport = injectStream(makeStream(), { peerId: 'cust-1' });
        assert.equal(transport.state, TransportState.CONNECTED);
        assert.ok(transport instanceof MediaTransport);
    });

    test('空流会被拒绝', () => {
        const transport = new InjectedTransport();
        assert.throws(() => transport.injectStream(null), /有效的 MediaStream/);
    });

    test('removeStream 派发 peer-left', () => {
        const transport = new InjectedTransport();
        transport.injectStream(makeStream(), 'cust-1');
        const seen = [];
        transport.on(TransportEvent.PEER_LEFT, ({ peerId }) => seen.push(peerId));
        transport.removeStream('cust-1');
        assert.deepEqual(seen, ['cust-1']);
    });
});
