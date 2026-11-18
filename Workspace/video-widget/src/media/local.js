/**
 * 本地媒体：摄像头、桌面采集、模拟流兜底。
 *
 * ★ MediaStream 只在本模块与 transport 内部流转，**绝不外泄**给业务侧。
 *
 * 三条基线判据：
 *  1. 桌面采集（`getDisplayMedia`）必须在**用户手势内**申请（§3.4）——
 *     本模块不缓存"稍后再取"，调用点必须就是点击处理器。
 *  2. **本地流复用**：`end()` 后轨道可能仍存活，复用时必须重新派发 `local`，
 *     否则预览挂不回去，后续每通只剩远端单向。
 *  3. 相机获取失败要有**模拟流兜底**（移动端）。
 */

/** 坐席摄像头规格：沿用基线的 960×540。 */
export const AGENT_VIDEO_CONSTRAINTS = { width: 960, height: 540 };

/** 坐席桌面采集规格：10fps 足够存证，再高只是徒增体积。 */
export const DESKTOP_CONSTRAINTS = { video: { frameRate: 10 }, audio: false };

/** 浏览器媒体错误名 → 人话提示；未知错误名也有文案，但**原始错误绝不丢**（挂 cause）。 */
const MEDIA_ERROR_HINTS = {
    NotAllowedError: '权限被拒绝，请在浏览器提示中允许摄像头/麦克风访问后重试',
    SecurityError: '当前页面上下文不允许访问媒体设备（需 HTTPS 或本机地址）',
    NotFoundError: '未找到可用的摄像头或麦克风设备',
    OverconstrainedError: '当前设备不满足采集规格要求，请更换设备或放宽参数',
    NotReadableError: '设备被其它应用占用或无法读取，请关闭占用后重试',
    AbortError: '媒体采集被中断，请重试',
    NotSupportedError: '当前浏览器不支持该媒体能力'
};

/**
 * 把浏览器媒体错误包装成人话错误：按 `error.name` 给针对性提示，
 * 同时保留原始错误（`cause`）——不再用一句通用文案盖掉真实原因。
 */
function mediaError(headline, cause, advice) {
    const name = cause?.name || 'Error';
    const hint = advice ?? MEDIA_ERROR_HINTS[name] ?? '请重试，若持续失败请联系管理员';
    const detail = cause?.message ?? String(cause);
    const error = new Error(`${headline}：${hint}（${name}：${detail}）`);
    error.cause = cause;
    return error;
}

/**
 * 释放路径的失败：清理继续做，引用照清，最后把失败如实合并抛出（不静默、不中断其余清理）。
 * 条目既可是 `{label, error}`（带环节名），也可直接是 Error / 字符串。
 */
function releaseError(headline, failures) {
    if (!failures.length) return null;
    const parts = failures.map(item => item?.label && item?.error
        ? `${item.label}：${item.error?.message ?? item.error}`
        : (item?.message ?? item));
    const error = new Error(`${headline}：${parts.join('；')}`);
    error.cause = failures[0]?.error ?? failures[0];
    return error;
}

export class LocalMedia {
    /**
     * @param {{mediaDevices?:object, document?:object, AudioContext?:Function}} options
     */
    constructor(options = {}) {
        this.mediaDevices = options.mediaDevices ?? globalThis.navigator?.mediaDevices ?? null;
        this.document = options.document ?? globalThis.document ?? null;
        this.AudioContext = options.AudioContext ?? globalThis.AudioContext ?? globalThis.webkitAudioContext ?? null;
        this.local = null;
        this.desktop = null;
        this.desktopVideo = null;
        this.audioContext = null;
        /** 是否用了模拟流：用于如实提示"当前画面为占位"，不冒充真实采集。 */
        this.usingMockStream = false;
        /** 回退模拟流时记录的真实取流失败原因，供 controller 经 error 事件如实上报。 */
        this.mockFallbackReason = null;
    }

    get hasLocal() {
        return Boolean(this.local && this.local.getVideoTracks?.().some(track => track.readyState === 'live'));
    }

    get hasDesktop() {
        return Boolean(this.desktop && this.desktop.getVideoTracks?.().some(track => track.readyState === 'live'));
    }

    /**
     * 获取本地摄像头流。
     * 已存在可用流时直接复用——重建会把已有连接里的轨道一并换掉。
     */
    async getUserMedia(constraints) {
        if (this.hasLocal) return this.local;
        if (!this.mediaDevices?.getUserMedia) throw new Error('请使用 HTTPS 或本机地址访问以开启摄像头');
        try {
            this.local = await this.mediaDevices.getUserMedia(constraints || { video: AGENT_VIDEO_CONSTRAINTS, audio: true });
        } catch (error) {
            throw mediaError('无法开启摄像头或麦克风', error);
        }
        this.usingMockStream = false;
        this.mockFallbackReason = null;
        return this.local;
    }

    /**
     * 移动端取流，宿主显式配置了 `mockStreamFactory` 时降级到模拟流。
     *
     * 降级顺序与基线一致：facingMode:'user' → 只要 video → 模拟流。
     * ★ 模拟流是**宿主显式选择**的退路，不是静默伪装：`usingMockStream` 置真，
     *   真实失败原因记进 `mockFallbackReason`，由 controller 经 error 事件如实上报。
     */
    async ensureStream(mockFactory) {
        if (this.hasLocal) return this.local;
        const attempts = [
            { video: { facingMode: 'user' }, audio: true },
            { video: true, audio: true },
            { video: true }
        ];
        let lastError = null;
        for (const constraints of attempts) {
            try {
                this.local = await this.mediaDevices.getUserMedia(constraints);
                this.usingMockStream = false;
                this.mockFallbackReason = null;
                return this.local;
            } catch (error) {
                lastError = error; // 逐档记录：最后一档的原因就是回退模拟流的真实原因
            }
        }
        if (typeof mockFactory === 'function') {
            this.local = mockFactory();
            this.usingMockStream = Boolean(this.local);
            if (this.local) {
                this.mockFallbackReason = lastError
                    ? `${lastError.name ?? 'Error'}：${lastError.message ?? lastError}`
                    : '未记录具体原因';
                return this.local;
            }
        }
        const error = new Error('无法开启摄像头，且当前环境不支持模拟流兜底'
            + (lastError ? `（${lastError.name ?? 'Error'}：${lastError.message ?? lastError}）` : ''));
        error.cause = lastError;
        throw error;
    }

    /**
     * 桌面采集。**必须在用户手势的同步调用链里**调用，否则浏览器直接拒绝。
     * 重复调用且上一段仍存活时直接复用。
     */
    async getDisplayMedia(options = {}) {
        if (this.hasDesktop) return this.desktop;
        if (!this.mediaDevices?.getDisplayMedia) throw new Error('当前浏览器不支持坐席桌面录制');
        try {
            this.desktop = await this.mediaDevices.getDisplayMedia(options.constraints || DESKTOP_CONSTRAINTS);
        } catch (error) {
            throw mediaError('人工见证需共享坐席桌面以完成三分屏录制', error);
        }
        // 桌面视频需要一个 video 元素才能真正解码出帧；直接拿 track 喂 canvas 拿不到 videoWidth。
        this.desktopVideo = this.createVideoElement();
        if (this.desktopVideo) {
            this.desktopVideo.muted = true;
            this.desktopVideo.playsInline = true;
            this.desktopVideo.srcObject = this.desktop;
            try {
                await this.desktopVideo.play?.();
            } catch (error) {
                // 解码起不来 = 合成画面里桌面永远是空的：先释放再如实报错，
                // 不能留一条"采集成功但没画面"的半成品给录制用。
                // 释放自身也失败时并进同一条错误，不许盖掉 play 失败这个主因。
                const wrapped = mediaError('坐席桌面画面启动失败，三分屏无法呈现桌面', error,
                    '画面无法自动启动（常见于浏览器自动播放策略拦截），请在用户手势内重试');
                try { this.releaseDesktop(); }
                catch (releaseFailure) { wrapped.message = `${wrapped.message}；桌面释放也失败：${releaseFailure.message}`; }
                throw wrapped;
            }
        }
        // 坐席点了浏览器的"停止共享"：整段存证就此中断，必须立刻封存而不是留一段没头没尾的录像。
        this.desktop.getVideoTracks?.()[0]?.addEventListener?.('ended', () => {
            options.onDesktopEnded?.();
        });
        return this.desktop;
    }

    createVideoElement() {
        if (!this.document?.createElement) return null;
        const video = this.document.createElement('video');
        video.muted = true;
        video.playsInline = true;
        return video;
    }

    /**
     * 把两路流的音频混成一路，供合成流使用。
     * 源：`agent_evidence.js` 的 AudioContext + MediaStreamDestination。
     */
    mixAudio(streams = []) {
        if (!this.AudioContext) return null;
        if (!this.audioContext) {
            try { this.audioContext = new this.AudioContext(); }
            catch (error) { return null; }
        }
        const destination = this.audioContext.createMediaStreamDestination?.();
        if (!destination) return null;
        this.mixedSources?.forEach(node => node.disconnect?.());
        this.mixedSources = new Map();this.mixDestination = destination;
        streams.filter(Boolean).forEach(stream => this.addMixedStream(stream));
        return destination.stream ?? null;
    }
    addMixedStream(stream) {
        if (!this.mixDestination || !stream?.getAudioTracks?.().length || this.mixedSources?.has(stream)) return;
        const source = this.audioContext.createMediaStreamSource(stream);source.connect(this.mixDestination);this.mixedSources.set(stream,source);
    }
    removeMixedStream(stream) { const source=this.mixedSources?.get(stream);source?.disconnect?.();this.mixedSources?.delete(stream); }

    /**
     * 切换本地轨道开关（静音 / 暂停画面）。
     * @returns {boolean|null} 切换后的状态；无轨道时返回 null（调用方据此提示"请先连接音视频"）
     */
    toggle(kind) {
        const tracks = kind === 'audio' ? this.local?.getAudioTracks?.() : this.local?.getVideoTracks?.();
        if (!tracks?.length) return null;
        const enabled = !tracks[0].enabled;
        tracks.forEach(track => { track.enabled = enabled; });
        return enabled;
    }

    /** 按接管模式开关视频轨：audio 模式下坐席视频不参与呈现，停轨省带宽。 */
    setVideoEnabled(enabled) {
        const tracks = this.local?.getVideoTracks?.() || [];
        tracks.forEach(track => { track.enabled = Boolean(enabled); });
        return tracks.length > 0;
    }

    releaseDesktop() {
        const failures = [];
        if (this.desktop) this.desktop.getTracks?.().forEach((track, index) => {
            // 每条轨道都尝试停（红线 §8.4-5），单条失败不中断其余清理，最后如实聚合抛出。
            try { track.stop(); } catch (error) { failures.push({ label: `停止桌面轨道 #${index}`, error }); }
        });
        if (this.desktopVideo) this.desktopVideo.srcObject = null;
        this.desktop = null;
        this.desktopVideo = null;
        const error = releaseError('释放坐席桌面', failures);
        if (error) throw error;
        return true;
    }

    /**
     * 释放本地流。
     * ★ 注意：stop() 之后轨道对象可能仍在（浏览器实现的差异），
     *   所以这里必须把引用一并清掉，逼迫下次走完整的 getUserMedia，
     *   避免复用到一个"看起来 live 实际没画面"的僵尸流。
     */
    releaseLocal() {
        const failures = [];
        if (this.local) this.local.getTracks?.().forEach((track, index) => {
            try { track.stop(); } catch (error) { failures.push({ label: `停止本地轨道 #${index}`, error }); }
        });
        this.local = null;
        this.usingMockStream = false;
        const error = releaseError('释放本地流', failures);
        if (error) throw error;
        return true;
    }

    async releaseAudio() {
        const failures = [];
        if (this.audioContext) {
            try { await this.audioContext.close(); }
            catch (error) { failures.push({ label: '关闭 AudioContext', error }); }
        }
        this.audioContext = null;
        this.mixDestination = null;this.mixedSources?.clear();
        const error = releaseError('释放音频上下文', failures);
        if (error) throw error;
        return true;
    }

    async release() {
        const failures = [];
        // 三路释放各自独立执行：一路失败不许中断其余清理（红线要求全部停掉）。
        try { this.releaseDesktop(); } catch (error) { failures.push(error); }
        try { this.releaseLocal(); } catch (error) { failures.push(error); }
        try { await this.releaseAudio(); } catch (error) { failures.push(error); }
        const error = releaseError('媒体释放未完全成功', failures);
        if (error) throw error;
        return true;
    }
}

/**
 * 移动端模拟流兜底（源：`mobile_balance.html` 的 createMobileMockStream）。
 * 返回 canvas.captureStream 的流；无 canvas 环境（如 node）返回 null。
 */
export function createMockStream(options = {}) {
    const document = options.document ?? globalThis.document;
    if (!document?.createElement) return null;
    const canvas = document.createElement('canvas');
    canvas.width = options.width || 480;
    canvas.height = options.height || 640;
    const context = canvas.getContext?.('2d');
    if (!context) return null;

    const draw = () => {
        context.fillStyle = '#0f172a';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.fillStyle = '#2563eb';
        context.beginPath();
        context.arc(35, 35, 8, 0, Math.PI * 2);
        context.fill();
        context.fillStyle = '#94a3b8';
        context.font = '16px sans-serif';
        context.fillText(options.caption || '客户移动终端实名现场', 52, 41);
        context.fillStyle = '#3b82f6';
        context.font = 'bold 24px sans-serif';
        context.textAlign = 'center';
        context.fillText(options.title || '客户实名认证现场', canvas.width / 2, canvas.height * 0.44);
        context.fillStyle = '#cbd5e1';
        context.font = '15px sans-serif';
        context.fillText(options.subtitle || '安全视频通道传输中', canvas.width / 2, canvas.height * 0.51);
    };
    draw();
    const stream = canvas.captureStream?.(options.frameRate || 25) ?? null;
    if (!stream) return null;
    // 模拟流没有真实音频轨；补一路静音轨，否则 MediaRecorder 可能只录到视频引发"有画面无声音"的误判。
    try {
        const AudioCtx = options.AudioContext ?? globalThis.AudioContext ?? globalThis.webkitAudioContext;
        if (AudioCtx) {
            const ctx = new AudioCtx();
            const dest = ctx.createMediaStreamDestination?.();
            dest?.stream?.getAudioTracks?.().forEach(track => stream.addTrack(track));
        }
    } catch (error) { /* 无音频轨不影响画面存证 */ }
    return stream;
}

export default LocalMedia;
