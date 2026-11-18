/*
 * JSBridge 宿主能力门面（契约 §6.2，接口定义见 ./contract.md）。
 *
 * 十能力域：media / album / ocr / face / signature / record / biometrics / device / navigate / telemetry。
 * SDK 定义接口、渠道方实现；宿主未实现的能力一律走 Web 兜底，缺失能力不阻断业务。
 * 所有方法返回 Promise，宿主未实现返回 null 时自动降级。
 *
 * 宿主实现两个来源，后者优先：
 *   1) window.VBBankingBridge（App 注入）
 *   2) mount(el, config) 的 config.bridge（页面内联）
 */

let host = null;

function capability(name) {
    const table = host;
    const value = table ? table[name] : null;
    return value && typeof value === 'object' ? value : null;
}

/** 逐方法解析：宿主实现了就调宿主，否则返回 null 让调用方走兜底。 */
function call(name, method, args) {
    const target = capability(name);
    const fn = target ? target[method] : null;
    if (typeof fn !== 'function') return null;
    try {
        return Promise.resolve(fn.apply(target, args));
    } catch (error) {
        // 宿主实现抛错按"未实现"处理，保证业务不被宿主缺陷卡死。
        console.warn(`[bridge] ${String(name)}.${method} 调用失败，转 Web 兜底:`, error);
        return null;
    }
}

export function installBridge(bridge) {
    const fromWindow = typeof window !== 'undefined' ? window.VBBankingBridge : null;
    host = bridge || fromWindow || null;
    return host;
}

export const bridge = {
    install: installBridge,

    /** 取流：宿主实现优先，其次 Web getUserMedia，最后画布模拟流。 */
    media: {
        async acquire(constraints = { video: { facingMode: 'user' }, audio: true }) {
            const hosted = call('media', 'acquire', [constraints]);
            if (hosted) return hosted;
            const devices = navigator && navigator.mediaDevices;
            if (devices && typeof devices.getUserMedia === 'function') {
                try {
                    return await devices.getUserMedia(constraints);
                } catch {
                    try {
                        return await devices.getUserMedia({ video: true, audio: true });
                    } catch {
                        /* 落到模拟流 */
                    }
                }
            }
            return mockStream();
        },
        async release(stream) {
            const hosted = call('media', 'release', [stream]);
            if (hosted) {
                await hosted;
                return;
            }
            if (stream && typeof stream.getTracks === 'function') stream.getTracks().forEach((track) => track.stop());
        },
    },

    album: {
        pick(options = {}) {
            const hosted = call('album', 'pick', [options]);
            if (hosted) return hosted;
            return webPick(options);
        },
    },

    ocr: {
        recognize(image, kind) {
            return call('ocr', 'recognize', [image, kind]);
        },
    },

    face: {
        verify(image, options) {
            return call('face', 'verify', [image, options]);
        },
    },

    signature: {
        open(options) {
            return call('signature', 'open', [options]);
        },
    },

    /** 录像分片：宿主实现优先；未实现返回 null，由 recorder 走 Web 上传。 */
    record: {
        append(chunk, meta) {
            return call('record', 'append', [chunk, meta]);
        },
    },

    biometrics: {
        auth(reason) {
            return call('biometrics', 'auth', [reason]);
        },
    },

    /**
     * 自助外设（身份证阅读器 / 凭条打印 / 扫码枪）：纯硬件能力，无 Web 兜底。
     * 宿主未实现返回 null，调用方降级为人工录入或拍照识别。
     */
    device: {
        readIdCard() {
            return call('device', 'readIdCard', []);
        },
        print(payload) {
            return call('device', 'print', [payload]);
        },
        scan() {
            return call('device', 'scan', []);
        },
    },

    navigate: {
        close() {
            return call('navigate', 'close', []);
        },
        to(target, params) {
            return call('navigate', 'to', [target, params]);
        },
    },

    telemetry: {
        log(event, payload) {
            return call('telemetry', 'log', [event, payload]);
        },
    },
};

/** Web 兜底选图：宿主未实现相册能力时使用。 */
function webPick(options = {}) {
    return new Promise((resolve, reject) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        if (options.source === 'camera') input.setAttribute('capture', 'environment');
        input.style.display = 'none';
        document.body.appendChild(input);
        input.onchange = () => {
            const file = input.files && input.files[0];
            if (input.parentNode) input.parentNode.removeChild(input);
            if (file) resolve(file);
            else reject(new Error('未选择照片'));
        };
        input.onerror = () => {
            if (input.parentNode) input.parentNode.removeChild(input);
            reject(new Error('无法打开相册'));
        };
        input.click();
    });
}

/**
 * 画布模拟流：仅在 getUserMedia 完全不可用时使用（原型演示环境）。
 * 与 navis-web createMobileMockStream 同口径，保证录制链路在没有摄像头时仍完整。
 */
function mockStream() {
    const canvas = document.createElement('canvas');
    canvas.width = 480;
    canvas.height = 640;
    const context = canvas.getContext('2d');
    const draw = () => {
        context.fillStyle = '#0f172a';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.fillStyle = '#3b82f6';
        context.font = 'bold 24px sans-serif';
        context.textAlign = 'center';
        context.fillText('客户实名认证现场', 240, 280);
        context.fillStyle = '#cbd5e1';
        context.font = '15px sans-serif';
        context.fillText('安全视频通道传输中', 240, 325);
    };
    draw();
    const timer = setInterval(draw, 1000);
    const stream = canvas.captureStream(25);
    const originalStop = stream.stop ? stream.stop.bind(stream) : null;
    return Object.assign(stream, {
        __mock: true,
        stop() {
            clearInterval(timer);
            if (originalStop) originalStop();
        },
    });
}

export default bridge;
