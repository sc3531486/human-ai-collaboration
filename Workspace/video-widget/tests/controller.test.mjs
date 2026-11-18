/**
 * 控制器状态机单测：命令 API 的签名与状态流转、抓帧/封存的返回值语义、
 * 布局落位、接管模式只按服务端下发执行。
 *
 * 用注入的依赖跑，全程无 DOM、无真实媒体——这正是"内核纯 JS"的意义。
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { createVideoController, MODE, TAKEOVER, CONNECTION } from '../src/controller.js';
import { ChunkQueue } from '../src/media/recorder.js';
import { NullSurface } from '../src/media/surface.js';
import { MediaTransport, TransportState, TransportEvent } from '../src/transport/MediaTransport.js';
import { registerTransport, resetTransports, InjectedTransport } from '../src/transport/registry.js';
import { makeStream, FakeIndexedDB, FakeBlob, FakeMediaRecorder, MemoryStorage, makeCanvas } from './helpers.js';

/** 假传输：记录调用，供测试决定何时派发 local / remote。 */
class FakeTransport extends MediaTransport {
    constructor(options = {}) {
        super(options);
        this.name = 'fake';
        this.calls = [];
        this.published = null;
        this.subscribed = [];
    }
    async connect() { this.setState(TransportState.CONNECTED); return this; }
    async publish(stream) { this.published = stream; this.calls.push('publish'); this.emit(TransportEvent.LOCAL, { stream }); return stream; }
    async subscribe(peerId) { this.subscribed.push(peerId); this.calls.push('subscribe'); return { peer: peerId }; }
    async hangup() { this.calls.push('hangup'); this.setState(TransportState.CLOSED); return true; }
}

function makeController(overrides = {}) {
    const transport = overrides.transport ?? new FakeTransport();
    const surface = overrides.surface ?? new NullSurface();
    const queue = overrides.queue ?? new ChunkQueue({
        indexedDB: new FakeIndexedDB(),
        Blob: FakeBlob,
        upload: overrides.upload ?? (async () => ({ success: true, fileSize: 8192 }))
    });
    const media = overrides.media ?? {
        local: null, desktop: null, desktopVideo: null, usingMockStream: false,
        hasLocal: false, hasDesktop: false,
        async getUserMedia() { this.local = makeStream(); this.hasLocal = true; return this.local; },
        async getDisplayMedia() { this.desktop = makeStream(['video']); this.hasDesktop = true; return this.desktop; },
        async ensureStream() { this.local = makeStream(); this.hasLocal = true; return this.local; },
        toggle() { return true; },
        setVideoEnabled() { return true; },
        mixAudio: () => null,
        async release() { this.local = null; this.desktop = null; return true; },
        releaseDesktop() { return true; }
    };
    return {
        transport,
        surface,
        queue,
        media,
        controller: createVideoController({
            transport, surface, queue, media,
            MediaRecorder: overrides.MediaRecorder ?? FakeMediaRecorder,
            Blob: FakeBlob,
            storage: overrides.storage ?? new MemoryStorage(),
            setInterval: overrides.setInterval,
            clearInterval: overrides.clearInterval,
            ...(overrides.controllerOptions || {})
        })
    };
}

describe('命令 API 完整性', () => {
    test('授权成员到达和退出不会替换客户，客户侧始终优先显示办理坐席', async () => {
        const {controller,transport,surface}=makeController();
        const members=[{peerId:'customer',role:'CUSTOMER'},{peerId:'agent',role:'AGENT'},{peerId:'reviewer',role:'AUTHORIZER'}];
        await controller.open();await controller.connect({localPeerId:'customer',participants:members,desktop:false,incomingOnly:true});
        const reviewerStream=makeStream(),agentStream=makeStream();
        transport.emit(TransportEvent.REMOTE,{peerId:'reviewer',stream:reviewerStream});
        transport.emit(TransportEvent.REMOTE,{peerId:'agent',stream:agentStream});
        assert.equal(surface.source('remote'),agentStream);assert.equal(surface.source('secondary'),reviewerStream);
        assert.equal(controller.getState().hasSecondaryRemote,true);
        assert.equal(controller.swapRemote(),true);
        assert.equal(surface.source('remote'),reviewerStream);assert.equal(surface.source('secondary'),agentStream);
        transport.emit(TransportEvent.PEER_LEFT,{peerId:'reviewer'});
        assert.equal(surface.source('remote'),agentStream);assert.equal(surface.source('secondary'),null);
        assert.equal(controller.getState().call,true);
        await controller.close();
    });
    test('媒体用户预注册不挂载窗口、不取摄像头、不外呼', async () => {
        const { controller, media, transport } = makeController();
        await controller.prepare({ localPeerId: 'cust-session-1' });
        assert.equal(controller.getState().mounted, false);
        assert.equal(media.local, null);
        assert.equal(media.desktop, null);
        assert.equal(transport.published, null);
        assert.deepEqual(transport.subscribed, []);
    });
    test('§2.9.3 的 12 个入口一个不少', () => {
        const { controller } = makeController();
        ['open', 'close', 'connect', 'hangup', 'takeover', 'toggle', 'capture', 'on', 'getState']
            .forEach(name => assert.equal(typeof controller[name], 'function', `缺少 ${name}`));
        assert.equal(typeof controller.record.start, 'function');
        assert.equal(typeof controller.record.seal, 'function');
    });

    test('getState 返回快照而不是内部引用', () => {
        const { controller } = makeController();
        const a = controller.getState();
        a.mounted = true;
        assert.equal(controller.getState().mounted, false);
    });
});

describe('open / close 状态机', () => {
    test('open 默认 overlay，挂载后 mounted 为 true', async () => {
        const { controller } = makeController();
        await controller.open();
        const state = controller.getState();
        assert.equal(state.mounted, true);
        assert.equal(state.mode, MODE.OVERLAY);
    });

    test('open 接受 dock 模式', async () => {
        const { controller } = makeController();
        await controller.open({ mode: 'dock', layout: { order: 1, heightRatio: 0.4 } });
        const state = controller.getState();
        assert.equal(state.mode, MODE.DOCK);
        assert.equal(state.layout.order, 1);
        assert.equal(state.layout.heightRatio, 0.4);
    });

    test('dock 模式下 layout 退化为 order / heightRatio', async () => {
        const { controller } = makeController();
        await controller.open({ mode: 'dock' });
        // 传了 overlay 的比例字段也不该被当成 dock 的布局
        await controller.open({ mode: 'dock', layout: { heightRatio: 0.5 } });
        const box = controller.resolveBox({ width: 400, height: 800 });
        assert.equal(box.order, 0);
        assert.equal(box.height, 400);
        assert.equal(box.width, undefined);
    });

    test('close 释放并把状态打回 idle', async () => {
        const { controller } = makeController();
        await controller.open();
        await controller.close();
        const state = controller.getState();
        assert.equal(state.mounted, false);
        assert.equal(state.connection, CONNECTION.IDLE);
        assert.equal(state.recording, false);
    });

    test('★ open 不申请任何媒体权限（同意告知书之前不得申请）', async () => {
        let asked = false;
        const { controller, media } = makeController();
        media.getUserMedia = async () => { asked = true; return makeStream(); };
        await controller.open();
        assert.equal(asked, false);
    });
});

describe('on / 状态订阅', () => {
    test('状态变更会派发 state 事件，返回的取消函数可退订', async () => {
        const { controller } = makeController();
        const seen = [];
        const off = controller.on('state', state => seen.push(state.mounted));
        await controller.open();
        off();
        await controller.close();
        assert.deepEqual(seen, [true]);
    });

    test('订阅者抛错不影响其它订阅者', async () => {
        const { controller } = makeController();
        const seen = [];
        controller.on('state', () => { throw new Error('boom'); });
        controller.on('state', () => seen.push(1));
        await controller.open();
        assert.equal(seen.length, 1);
    });
});

describe('connect / hangup', () => {
    test('connect 会取本地流、发布、并按 peerId 订阅', async () => {
        const { controller, transport } = makeController();
        await controller.open();
        await controller.connect({ peerId: 'cust-1', processId: 'P1' });
        const state = controller.getState();
        assert.equal(state.hasLocal, true);
        assert.equal(state.peerId, 'cust-1');
        assert.equal(state.processId, 'P1');
        assert.ok(transport.calls.includes('publish'));
        assert.deepEqual(transport.subscribed, ['cust-1']);
    });

    test('★ 桌面采集在 connect 内申请（坐席点击接听的用户手势内）', async () => {
        const { controller, media } = makeController();
        let asked = false;
        media.getDisplayMedia = async () => { asked = true; return makeStream(['video']); };
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        assert.equal(asked, true, 'getDisplayMedia 必须在接听手势内申请');
    });

    test('无 peerId 时走本地见证：不订阅，call 保持 false', async () => {
        const { controller, transport } = makeController();
        await controller.open();
        await controller.connect({ processId: 'P1' });
        assert.equal(transport.subscribed.length, 0);
        assert.equal(controller.getState().call, false);
    });

    test('远端流到达才算通话中', async () => {
        const { controller, transport } = makeController();
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        assert.equal(controller.getState().call, false);
        const stream = makeStream();
        transport.emit(TransportEvent.REMOTE, { stream, peerId: 'cust-1' });
        const state = controller.getState();
        assert.equal(state.call, true);
        assert.equal(state.hasRemote, true);
    });

    test('hangup 后回到 ended 并清掉远端', async () => {
        const { controller, transport } = makeController();
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        transport.emit(TransportEvent.REMOTE, { stream: makeStream(), peerId: 'cust-1' });
        await controller.hangup();
        const state = controller.getState();
        assert.equal(state.call, false);
        assert.equal(state.hasRemote, false);
        assert.equal(state.connection, CONNECTION.ENDED);
        assert.ok(transport.calls.includes('hangup'));
    });

    test('连接失败时置 error 状态并抛出，不静默', async () => {
        const { controller, media } = makeController();
        media.getUserMedia = async () => { throw new Error('无法开启摄像头或麦克风，请检查设备权限后重试'); };
        await controller.open();
        await assert.rejects(() => controller.connect({ peerId: 'cust-1' }), /摄像头/);
        assert.match(controller.getState().error, /摄像头/);
    });
});

describe('takeover / toggle', () => {
    test('接管模式只接受服务端下发的三种值', async () => {
        const { controller } = makeController();
        await controller.open();
        assert.equal(await controller.takeover('av'), TAKEOVER.AV);
        assert.equal(await controller.takeover('audio'), TAKEOVER.AUDIO);
        assert.equal(await controller.takeover('released'), TAKEOVER.RELEASED);
        assert.equal(controller.getState().takeover, TAKEOVER.RELEASED);
        await assert.rejects(() => controller.takeover('video'), /不支持的接管模式/);
    });

    test('★ 控件不下业务判断：audio 模式停视频轨，av 恢复', async () => {
        let enabled = null;
        const { controller, media } = makeController();
        media.setVideoEnabled = value => { enabled = value; return true; };
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        // 未通话时不改轨道；通话中才按模式切换
        await controller.takeover('audio');
        assert.equal(enabled, null, '未通话不下判断');
    });

    test('toggle 返回切换后的状态', async () => {
        const { controller } = makeController();
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        assert.equal(await controller.toggle('audio'), true);
        assert.equal(controller.getState().audioEnabled, true);
    });

    test('toggle 非法 kind 报错', async () => {
        const { controller } = makeController();
        await assert.rejects(() => controller.toggle('screen'), /不支持的开关/);
    });
});

describe('capture：返回 Blob 语义', () => {
    test('有画面时返回 Blob', async () => {
        const { controller, surface } = makeController({
            controllerOptions: {
                // 内核不建 canvas（node 无 document）：由宿主注入画布与 toBlob 管线，
                // 与 tests/capture.test.mjs 的注入方式一致。
                captureOptions: { canvas: makeCanvas(), toBlob: cb => cb(new FakeBlob(['png'], { type: 'image/png' })) }
            }
        });
        await controller.open();
        surface.attach('remote', { readyState: 4, videoWidth: 1280, videoHeight: 720 });
        const blob = await controller.capture('remote');
        assert.ok(blob);
        assert.equal(blob.type, 'image/png');
    });

    test('★ 没有画面时返回 null，不伪造占位图', async () => {
        const { controller } = makeController();
        await controller.open();
        assert.equal(await controller.capture('remote'), null);
    });

    test('非法抓帧源报错', async () => {
        const { controller } = makeController();
        await assert.rejects(() => controller.capture('desktop'), /不支持的抓帧源/);
    });
});

/**
 * record 红线组。★ 调用方义务变更（兜底移除）：这些用例跑在 node + NullSurface（无画布），
 * 而 connect 的假媒体总是授予桌面 → 默认走「三分屏存证」会如实报错
 * （controller.startComposite：画布不可用就抛，不再静默改录原始流）。
 * 因此这里显式传 { composite: false }——这正是移除兜底后契约要求的调用方选择。
 * ★ 断言语义零改动：四条红线断言（空分片必报 / fileSize===0 success:false / 真实 fileSize）原样保留。
 */
describe('record：空分片上报与 0 字节如实回执', () => {
    test('start 返回 recordingId，recording 置真', async () => {
        const { controller } = makeController();
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        const result = await controller.record.start({ processId: 'P1', bizNo: 'B1', nodeName: '人工授权', composite: false });
        assert.ok(result.recordingId);
        assert.equal(controller.getState().recording, true);
    });

    test('★ 空分片被上报（队列里能查到 size 为 0 的分片）', async () => {
        const { controller, queue } = makeController();
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        await controller.record.start({ processId: 'P1', composite: false });
        const recorder = controller.recorder;
        FakeMediaRecorder.instances.at(-1).feed(0);
        assert.equal(recorder.emptyChunks, 2, '首片空分片 + 这一片空分片');
        assert.equal(recorder.reportedChunks, 2, '★ 空分片同样上报');
        assert.ok(queue);
    });

    test('★ seal 的 fileSize === 0 时如实返回，不宣告成功', async () => {
        const { controller } = makeController({
            upload: async () => ({ success: true, fileSize: 0 })
        });
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        await controller.record.start({ processId: 'P1', composite: false });
        const receipt = await controller.record.seal();
        assert.equal(receipt.fileSize, 0);
        assert.equal(receipt.success, false, '0 字节不得宣告存证成功');
        assert.match(receipt.message, /未采集到画面/);
        assert.equal(controller.getState().recording, false);
    });

    test('有内容时 seal 返回真实 fileSize', async () => {
        const { controller } = makeController();
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        await controller.record.start({ processId: 'P1', composite: false });
        const receipt = await controller.record.seal();
        assert.equal(receipt.fileSize, 8192);
        assert.equal(receipt.success, true);
    });

    test('未录制时 seal 返回 0 字节回执而不是抛错', async () => {
        const { controller } = makeController();
        const receipt = await controller.record.seal();
        assert.equal(receipt.fileSize, 0);
        assert.equal(receipt.success, false);
    });

    test('resume 可补传同一 recordingId 的待传分片', async () => {
        const { controller, queue } = makeController();
        await queue.operation('readwrite', store => store.put({
            key: 'r1:0', recordingId: 'r1', chunkIndex: 0, startedAt: 1,
            blob: new FakeBlob(['aaa']), bizNo: 'B1', isLast: true
        }));
        const receipt = await controller.record.resume();
        assert.equal(receipt.fileSize, 8192);
        assert.deepEqual(await queue.pending(), []);
    });
});

describe('布局：落位与持久化', () => {
    test('resolveBox 按容器换算并 clamp', async () => {
        const { controller } = makeController();
        await controller.open({ layout: { left: 0.5, top: 0.5, width: 0.3, height: 0.3 } });
        const box = controller.resolveBox({ width: 1000, height: 800 });
        assert.deepEqual(box, { left: 500, top: 400, width: 300, height: 240 });
    });

    test('★ 拖拽落位回写归一化值并持久化', async () => {
        const storage = new MemoryStorage();
        const { controller } = makeController({ storage });
        await controller.open({ draggable: true });
        const next = controller.moveTo({ left: 500, top: 200, width: 300, height: 240 }, { width: 1000, height: 800 });
        assert.deepEqual(next, { left: 0.5, top: 0.25, width: 0.3, height: 0.3 });
        assert.ok(storage.getItem('bank.video.layout.v1'), '落位必须持久化');
    });

    test('dock 模式下 moveTo 不改布局（流式布局由父容器决定）', async () => {
        const { controller } = makeController();
        await controller.open({ mode: 'dock', layout: { order: 2 } });
        const next = controller.moveTo({ left: 500, top: 200, width: 300, height: 240 }, { width: 1000, height: 800 });
        assert.equal(next.order, 2);
    });

    test('restoreLayout 能从持久化读回落位', async () => {
        const storage = new MemoryStorage();
        const { controller } = makeController({ storage });
        await controller.open();
        controller.moveTo({ left: 400, top: 100, width: 300, height: 240 }, { width: 1000, height: 800 });
        const fresh = makeController({ storage }).controller;
        await fresh.open();
        const restored = fresh.restoreLayout();
        assert.equal(restored.left, 0.4);
    });
});

describe('传输可替换', () => {
    test('★ 换传输实现只传逻辑名，业务侧不出现厂商字样', async () => {
        resetTransports();
        class VendorTransport extends MediaTransport {
            async connect() { this.setState(TransportState.CONNECTED); return this; }
            async publish(stream) { return stream; }
            async subscribe() { return null; }
            async hangup() { this.setState(TransportState.CLOSED); return true; }
        }
        registerTransport('vendor', VendorTransport);
        const { controller } = makeController();
        const instance = controller.useTransport('vendor');
        assert.equal(instance.name, 'vendor');
        assert.ok(instance instanceof MediaTransport);
        resetTransports();
    });

    test('未登记的实现名会明确报错', () => {
        resetTransports();
        const { controller } = makeController();
        assert.throws(() => controller.useTransport('nope'), /未登记的传输实现/);
        resetTransports();
    });

    test('injectStream：渠道方已有流时直接注入，控件照常工作', async () => {
        // 渠道方注入的正路是注入式传输。原用例跑在 FakeTransport（无 injectStream）上，
        // 断言的正是被移除的兜底行为（静默跳过传输注入仍把 call 置真）——现改为真路夹具，
        // 三条原断言语义不变；兜底行为本身由下一条用例锁定为「如实报错」。
        const transport = new InjectedTransport();
        const { controller, surface } = makeController({ transport });
        await controller.open();
        const stream = makeStream();
        controller.injectStream(stream, 'cust-9');
        const state = controller.getState();
        assert.equal(state.hasRemote, true);
        assert.equal(state.call, true);
        assert.equal(surface.source('remote'), stream);
    });

    test('★ 不支持注入的传输如实报错，不再静默把 call 置真', async () => {
        const { controller } = makeController(); // FakeTransport 未实现 injectStream
        await controller.open();
        assert.throws(() => controller.injectStream(makeStream(), 'cust-9'), /不支持注入外部流/);
        const state = controller.getState();
        assert.equal(state.call, false, '注入失败不得把通话状态置真');
        assert.equal(state.hasRemote, false, '注入失败不得谎报已有远端画面');
    });

    test('★ W1/W2：注入式链路 connect→publish→subscribe 后 connection=connected，hangup 后 ended', async () => {
        resetTransports();
        const transport = new InjectedTransport();
        const { controller } = makeController({ transport });
        await controller.open();
        controller.injectStream(makeStream(), 'cust-1');
        // 注入画面 ≠ 已建链：connect() 被 controller 驱动之前，connection 必须诚实停在 idle。
        assert.equal(controller.getState().connection, CONNECTION.IDLE);
        await controller.connect({ peerId: 'cust-1', processId: 'P1' });
        // ★ 回归锚点：InjectedTransport.connect 由 controller 在 publish 前驱动（W1），
        //   快照 connection 不再整通恒为 idle（W2），未来按它做质检/时长统计才不漏。
        assert.equal(controller.getState().connection, CONNECTION.CONNECTED);
        assert.equal(controller.getState().call, true);
        await controller.hangup();
        assert.equal(controller.getState().connection, CONNECTION.ENDED);
        assert.equal(controller.getState().call, false);
        resetTransports();
    });
});

describe('MediaStream 不外泄', () => {
    test('★ surface 之外拿不到流：state 快照里没有任何 MediaStream', async () => {
        const { controller } = makeController();
        await controller.open();
        await controller.connect({ peerId: 'cust-1' });
        const snapshot = controller.getState();
        Object.values(snapshot).forEach(value => {
            assert.ok(!(value && typeof value === 'object' && typeof value.getTracks === 'function'),
                'state 快照不得携带 MediaStream');
        });
    });

    test('getState 的键都是可序列化的状态字段', async () => {
        const { controller } = makeController();
        await controller.open();
        assert.doesNotThrow(() => JSON.stringify(controller.getState()));
    });
});
