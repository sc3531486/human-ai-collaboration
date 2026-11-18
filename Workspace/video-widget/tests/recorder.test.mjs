/**
 * 录制语义单测：★ 空分片也要上报、分片落 IndexedDB 再顺序上传、
 * 重载补传同一 recordingId、0 字节回执如实返回。
 *
 * 这些是契约 §3.4 里带业务原因的判据，是最容易在重构中被"顺手优化"掉的部分。
 */
import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

import { ChunkQueue, SegmentRecorder, makeRecordingId, CHUNK_FIELDS } from '../src/media/recorder.js';
import { FakeIndexedDB, FakeBlob, FakeMediaRecorder, makeStream } from './helpers.js';

/** 假上传器：记录收到的 FormData，最后一票决定 fileSize，便于构造 0 字节场景。 */
function makeUpload(options = {}) {
    const calls = [];
    const upload = async (form, item) => {
        calls.push({
            fields: Object.fromEntries([...form.entries()].map(([k, v]) => [k, typeof v === 'string' ? v : v?.size ?? ''])),
            size: item.blob?.size ?? 0,
            item
        });
        if (options.failOn && options.failOn(item)) throw new Error('网络异常');
        return { success: true, fileSize: options.fileSize ?? 4096, fileHash: 'sha-abc' };
    };
    return { upload, calls };
}

function makeQueue(overrides = {}) {
    const { upload, calls } = makeUpload(overrides);
    const queue = new ChunkQueue({
        indexedDB: new FakeIndexedDB(),
        Blob: FakeBlob,
        upload,
        ...overrides
    });
    return { queue, calls };
}

beforeEach(() => { FakeMediaRecorder.instances.length = 0; });

describe('ChunkQueue：先落库再顺序上传', () => {
    test('分片先写入 IndexedDB，上传成功后删除', async () => {
        const { queue, calls } = makeQueue();
        await queue.enqueue({ recordingId: 'r1', chunkIndex: 0, blob: new FakeBlob(['aaa']), bizNo: 'B1' });
        assert.equal(calls.length, 1);
        assert.deepEqual(await queue.pending(), []);
    });

    test('按 startedAt 再按 chunkIndex 顺序上传（重载补传时两段混在一起也不乱序）', async () => {
        const { queue, calls } = makeQueue();
        await queue._db; // 触发 open，确保后续 put 落到同一 store
        await queue.operation('readwrite', store => store.put({ key: 'r:9', recordingId: 'r', chunkIndex: 9, startedAt: 200, blob: new FakeBlob(['new']), bizNo: 'B' }));
        await queue.operation('readwrite', store => store.put({ key: 'r:1', recordingId: 'r', chunkIndex: 1, startedAt: 100, blob: new FakeBlob(['old']), bizNo: 'B' }));
        await queue.flush();
        assert.deepEqual(calls.map(call => call.item.chunkIndex), [1, 9]);
    });

    test('上传失败时分片仍留在库里，可再次 flush 补传', async () => {
        const { queue } = makeQueue({ failOn: () => true });
        await queue.enqueue({ recordingId: 'r1', chunkIndex: 0, blob: new FakeBlob(['aaa']) });
        const pending = await queue.pending();
        assert.equal(pending.length, 1);
        assert.equal(pending[0].recordingId, 'r1');
    });

    test('FormData 字段与后端 stream-chunk 契约一致', async () => {
        const { queue, calls } = makeQueue();
        await queue.enqueue({
            recordingId: 'r1', chunkIndex: 0, blob: new FakeBlob(['aaa']),
            bizNo: 'B1', processId: 'P1', segmentIndex: 2, handlerType: 'HUMAN_AGENT',
            handlerName: '远程坐席', duration: 12, nodesCovered: '人工授权', isLast: false
        });
        const fields = calls[0].fields;
        CHUNK_FIELDS.forEach(name => assert.ok(name in fields, `缺少字段 ${name}`));
        assert.equal(fields.bizNo, 'B1');
        assert.equal(fields.recordingId, 'r1');
    });
});

describe('SegmentRecorder：★ 空分片同样上报', () => {
    test('start 时先登记一片空分片（保留"本段已开始"的顺序标识）', async () => {
        const { queue, calls } = makeQueue();
        const recorder = new SegmentRecorder({ queue, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        await recorder.start(makeStream(), { bizNo: 'B1', processId: 'P1' });
        assert.equal(calls.length, 1, '首片空分片必须入队');
        assert.equal(calls[0].size, 0, '首片确实是空分片');
        assert.equal(recorder.emptyChunks, 1);
    });

    test('★ ondataavailable 的空分片不被过滤（size > 0 过滤会重现"0 字节却报成功"）', async () => {
        const { queue, calls } = makeQueue();
        const recorder = new SegmentRecorder({ queue, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        await recorder.start(makeStream(), { bizNo: 'B1' });
        const instance = FakeMediaRecorder.instances.at(-1);

        instance.feed(0); // ★ 空分片
        instance.feed(120);
        instance.feed(0); // ★ 又一个空分片

        // 分片上报是 fire-and-forget（录制不被网络拖住），断言前等上报链排空。
        // 只加等待点，不改断言：calls 必须包含 size 为 0 的分片（红线不变）。
        await recorder.whenIdle();

        assert.equal(recorder.emptyChunks, 3, '三片空分片都应计数');
        assert.equal(recorder.reportedChunks, 4, '空分片同样上报，不得被 size>0 过滤');
        assert.equal(calls.length, 4);
        assert.deepEqual(calls.map(call => call.size), [0, 0, 120, 0]);
    });

    test('非空分片照常上报且 chunkIndex 递增', async () => {
        const { queue, calls } = makeQueue();
        const recorder = new SegmentRecorder({ queue, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        await recorder.start(makeStream(), {});
        const instance = FakeMediaRecorder.instances.at(-1);
        instance.feed(100);
        instance.feed(200);
        await recorder.whenIdle(); // 等上报链排空（只加等待点，不改断言）
        assert.deepEqual(calls.map(call => call.item.chunkIndex), [0, 1, 2]);
    });
});

describe('SegmentRecorder：封存回执', () => {
    test('seal 返回含 fileSize 的回执', async () => {
        const { queue } = makeQueue({ fileSize: 20480 });
        const recorder = new SegmentRecorder({ queue, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        await recorder.start(makeStream(), { bizNo: 'B1' });
        const receipt = await recorder.seal();
        assert.equal(receipt.success, true);
        assert.equal(receipt.fileSize, 20480);
        assert.ok(receipt.recordingId);
    });

    test('★ fileSize === 0 时如实返回，success 落 false，不得宣告成功', async () => {
        const { queue } = makeQueue({ fileSize: 0 });
        const recorder = new SegmentRecorder({ queue, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        await recorder.start(makeStream(), { bizNo: 'B1' });
        const receipt = await recorder.seal();
        assert.equal(receipt.fileSize, 0);
        assert.equal(receipt.success, false, '0 字节不得宣告成功');
        assert.match(receipt.message, /未采集到画面/);
    });

    test('★ 录满 40 秒却 0 字节的场景：全部空分片上报后仍如实反映 0 字节', async () => {
        const { queue, calls } = makeQueue({ fileSize: 0 });
        const recorder = new SegmentRecorder({ queue, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        await recorder.start(makeStream(), { bizNo: 'B1' });
        const instance = FakeMediaRecorder.instances.at(-1);
        for (let i = 0; i < 26; i += 1) instance.feed(0); // 1.5s × 26 ≈ 40 秒，全部空
        const receipt = await recorder.seal();
        assert.ok(calls.length > 20, '40 秒里每一片都上报了');
        assert.equal(receipt.fileSize, 0, '落盘 0 字节必须如实反映在回执里');
        assert.equal(receipt.success, false);
    });

    test('未开始时 seal 返回 0 字节回执而不是抛错', async () => {
        const { queue } = makeQueue();
        const recorder = new SegmentRecorder({ queue, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        const receipt = await recorder.seal();
        assert.equal(receipt.fileSize, 0);
        assert.equal(receipt.success, false);
    });

    test('seal 会等待队列排空（未排空不得提前返回）', async () => {
        let released = false;
        const { queue } = makeQueue();
        const slow = new ChunkQueue({
            indexedDB: new FakeIndexedDB(),
            Blob: FakeBlob,
            upload: async () => {
                await new Promise(resolve => setTimeout(resolve, 20));
                released = true;
                return { success: true, fileSize: 1024 };
            }
        });
        const recorder = new SegmentRecorder({ queue: slow, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        await recorder.start(makeStream(), {});
        const receipt = await recorder.seal();
        assert.equal(released, true);
        assert.equal(receipt.fileSize, 1024);
        assert.ok(queue);
    });
});

describe('SegmentRecorder：其它边界', () => {
    test('重复 start 不会建第二个 recorder', async () => {
        const { queue } = makeQueue();
        const recorder = new SegmentRecorder({ queue, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        await recorder.start(makeStream(), {});
        await recorder.start(makeStream(), {});
        assert.equal(FakeMediaRecorder.instances.length, 1);
    });

    test('无 MediaRecorder 时明确报错', async () => {
        const { queue } = makeQueue();
        const recorder = new SegmentRecorder({ queue, MediaRecorder: null, Blob: FakeBlob });
        await assert.rejects(() => recorder.start(makeStream(), {}), /不支持录制/);
    });

    test('无流时明确报错', async () => {
        const { queue } = makeQueue();
        const recorder = new SegmentRecorder({ queue, MediaRecorder: FakeMediaRecorder, Blob: FakeBlob });
        await assert.rejects(() => recorder.start(null, {}), /未提供可录制的媒体流/);
    });

    test('不支持的封装格式时报错', async () => {
        const { queue } = makeQueue();
        const Unsupported = { isTypeSupported: () => false };
        const recorder = new SegmentRecorder({
            queue, MediaRecorder: Unsupported, Blob: FakeBlob, mimeCandidates: ['video/nope']
        });
        await assert.rejects(() => recorder.start(makeStream(), {}), /不支持当前录像格式/);
    });

    test('recordingId 生成带前缀且唯一', () => {
        const a = makeRecordingId('agent');
        const b = makeRecordingId('agent');
        assert.match(a, /^agent-/);
        assert.notEqual(a, b);
    });
});
