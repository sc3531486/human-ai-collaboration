/**
 * LocalMedia 释放路径红线（契约 §8.4-5：hangup 停全部 track）：
 * 清理必须完整——每条轨道都尝试停，单条失败不中断其余清理；
 * 失败不许静默——引用照清，最后如实聚合抛出（移除"stop 失败就忽略"的兜底）。
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { LocalMedia } from '../src/media/local.js';

function makeTrack(shouldThrow) {
    return { readyState: 'live', stop() { if (shouldThrow) throw new Error('轨道停止失败'); } };
}

function makeMedia() {
    const media = new LocalMedia({ mediaDevices: {}, document: null, AudioContext: null });
    media.local = {
        getTracks: () => [makeTrack(false), makeTrack(true)],
        getVideoTracks: () => [makeTrack(false)],
        getAudioTracks: () => []
    };
    media.desktop = {
        getTracks: () => [makeTrack(true)],
        getVideoTracks: () => [makeTrack(true)]
    };
    media.desktopVideo = { srcObject: {} };
    media.audioContext = { close: async () => { throw new Error('AudioContext 关闭失败'); } };
    return media;
}

describe('LocalMedia：停全部轨道，失败如实聚合', () => {
    test('★ 单条轨道 stop 失败不中断其余清理，最后聚合抛出且引用照清', async () => {
        const media = makeMedia();
        await assert.rejects(
            () => media.release(),
            error => /媒体释放未完全成功/.test(error.message)
                && /坐席桌面/.test(error.message)
                && /停止桌面轨道/.test(error.message)
                && /本地流/.test(error.message)
                && /停止本地轨道/.test(error.message)
                && /AudioContext/.test(error.message)
        );
        // 引用必须清掉：失败也不许留下"看起来还活着"的僵尸流。
        assert.equal(media.local, null, '失败后本地流引用必须清空');
        assert.equal(media.desktop, null, '失败后桌面流引用必须清空');
        assert.equal(media.desktopVideo, null, '失败后桌面 video 元素引用必须清空');
        assert.equal(media.audioContext, null, '失败后音频上下文引用必须清空');
    });

    test('★ releaseDesktop 单独调用也如实报错（seal 收尾路径会直接调它）', async () => {
        const media = makeMedia();
        assert.throws(() => media.releaseDesktop(), /释放坐席桌面：停止桌面轨道/);
        assert.equal(media.desktop, null);
        assert.equal(media.desktopVideo, null);
    });

    test('全部正常时 release 不抛错', async () => {
        const media = new LocalMedia({ mediaDevices: {}, document: null, AudioContext: null });
        media.local = { getTracks: () => [makeTrack(false)], getVideoTracks: () => [], getAudioTracks: () => [] };
        assert.equal(await media.release(), true);
        assert.equal(media.local, null);
    });
});
