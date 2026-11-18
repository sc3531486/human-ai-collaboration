/*
 * 客户本地流媒体兜底（契约 §3.6 / §8.4）：
 *   - 同意告知书之前绝不取流（红线）
 *   - 宿主原生取流走 JSBridge media.acquire，Web 兜底 getUserMedia，最后画布模拟流
 *   - 控件（video-widget）接入后，抓帧与本地预览优先控件，本模块仅作控件缺失时的降级
 */
import bridge from '../bridge/index.js';

export function createMedia(refs, options = {}) {
    const getConsent = options.getConsent || (() => false);
    let localStream = null;
    let acquiring = null;

    async function ensureLocalStream() {
        if (!getConsent()) throw new Error('请先阅读并同意告知书后再开启摄像头');
        if (localStream) return localStream;
        if (acquiring) return acquiring;
        acquiring = (async () => {
            const stream = await bridge.media.acquire({ video: { facingMode: 'user' }, audio: true });
            localStream = stream;
            if (refs.pip) {
                refs.pip.srcObject = stream;
                refs.pip.play?.().catch(() => {});
                if (!refs.widgetActive) refs.pip.style.display = 'block';
            }
            return stream;
        })();
        try {
            return await acquiring;
        } finally {
            acquiring = null;
        }
    }

    /**
     * 本地兜底抓帧：仅画本模块自己的预览画面。
     * 控件优先的次序由 video.capture('local')（video-widget 适配层）负责——
     * 适配层先问控件、再回落本函数；本函数不得反向回调适配层，否则无限递归。
     */
    async function captureFrame() {
        await ensureLocalStream();
        const video = refs.pip;
        if (!video || !video.videoWidth) throw new Error('摄像头画面尚未就绪，请稍后重试');
        const canvas = document.createElement('canvas');
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        canvas.getContext('2d').drawImage(video, 0, 0);
        const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
        if (!blob) throw new Error('照片生成失败，请重试');
        return blob;
    }

    async function release() {
        if (refs.pip) {
            refs.pip.srcObject = null;
            refs.pip.style.display = 'none';
        }
        if (localStream) {
            await bridge.media.release(localStream);
            localStream = null;
        }
    }

    return {
        ensureLocalStream,
        captureFrame,
        release,
        get stream() { return localStream; },
        /** 视频可用性：控件在用或本地兜底流存在。 */
        available() {
            return Boolean(localStream);
        },
    };
}

export default createMedia;
