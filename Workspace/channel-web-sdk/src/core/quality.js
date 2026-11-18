/*
 * 质量分级（契约 §8.3）。
 *
 * 启动探测 hardwareConcurrency / deviceMemory / MediaRecorder 编码能力定档（高/中/低），
 * 控制采集分辨率（640×480 起步）、帧率、码率上限与动画开关；运行时按丢帧与码率自适应降档。
 * 渠道方可通过 mount 配置覆盖。
 *
 * 编码探测：VP8 不可用回落 H.2.64，都不可用走宿主 JSBridge 的 record。
 */

const PROFILES = {
    high: { tier: 'high', width: 1280, height: 720, frameRate: 30, bitrateKbps: 2500, animate: true },
    medium: { tier: 'medium', width: 640, height: 480, frameRate: 24, bitrateKbps: 1200, animate: true },
    low: { tier: 'low', width: 640, height: 480, frameRate: 15, bitrateKbps: 600, animate: false },
};

const MIME_CANDIDATES = [
    { mime: 'video/webm;codecs=vp8,opus', codec: 'vp8' },
    { mime: 'video/webm;codecs=vp9,opus', codec: 'vp8' },
    { mime: 'video/mp4;codecs=h264,aac', codec: 'h264' },
    { mime: 'video/mp4', codec: 'h264' },
];

/** 编码能力探测：VP8 → H.264 → 宿主桥接。 */
export function detectCodec() {
    const recorder = typeof window !== 'undefined' ? window.MediaRecorder : null;
    if (!recorder || typeof recorder.isTypeSupported !== 'function') return { mimeType: '', codec: 'bridge' };
    for (const candidate of MIME_CANDIDATES) {
        try {
            if (recorder.isTypeSupported(candidate.mime)) return { mimeType: candidate.mime, codec: candidate.codec };
        } catch {
            /* 老 WebView 的 isTypeSupported 可能抛错，继续探测下一个 */
        }
    }
    return { mimeType: '', codec: 'bridge' };
}

export function detectTier() {
    const nav = typeof navigator !== 'undefined' ? navigator : {};
    const cores = Number(nav.hardwareConcurrency || 0);
    const memory = Number(nav.deviceMemory || nav.deviceMemoryGB || 0);
    if (cores >= 8 && (memory === 0 || memory >= 4)) return 'high';
    if (cores >= 4 || memory >= 2) return 'medium';
    return 'low';
}

let current = resolve();

function resolve(override) {
    const tier = typeof override === 'string' ? override : override?.tier || detectTier();
    const base = PROFILES[tier] || PROFILES.low;
    const codec = detectCodec();
    return {
        ...base,
        ...(typeof override === 'object' ? override : {}),
        mimeType: (typeof override === 'object' && override.mimeType) || codec.mimeType,
        codec: (typeof override === 'object' && override.codec) || codec.codec,
    };
}

export const quality = {
    configure(override) {
        current = resolve(override);
        return current;
    },
    get() {
        return current;
    },
    /** 运行时自适应降档：丢帧率或码率超阈值时降一档，不再自动回升（避免抖动）。 */
    degrade() {
        const order = ['high', 'medium', 'low'];
        const index = order.indexOf(current.tier);
        if (index < order.length - 1) current = resolve(order[index + 1]);
        return current;
    },
};

export default quality;
