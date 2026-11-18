/**
 * 抓帧与三分屏合成（源：`agent_evidence.js` 的 capture / drawCover / 合成循环）。
 *
 * ★ 纯 JS、框架无关，但对 Canvas 的依赖是**注入**的：
 *   单测里给一个假 canvas/context 就能断言 drawCover 的裁切数字与 capture 的 Blob 语义。
 *
 * 两条判据：
 *  - 合成按 `object-fit: cover` 等比裁切（drawCover），**不硬拉伸**：
 *    坐席摄像头 16:9、手机端竖屏，直接 drawImage 到目标矩形会把两路都压扁。
 *  - `capture()` 返回 **Blob**（契约 §2.9.3）。拿不到画面时返回 null——
 *    **不得**伪造一张占位图冒充成功。
 */

export const COMPOSITE_WIDTH = 960;
export const COMPOSITE_HEIGHT = 540;

/**
 * 等比铺满目标区域后居中裁切（等价 CSS `object-fit: cover`）。
 *
 * @param {CanvasRenderingContext2D} context
 * @param {{videoWidth:number, videoHeight:number}} source 视频源（video 元素或具备宽高的对象）
 * @param {number} dx 目标左上 x
 * @param {number} dy 目标左上 y
 * @param {number} dw 目标宽
 * @param {number} dh 目标高
 * @returns {boolean} 是否真的画了（源还没有尺寸时返回 false）
 */
export function drawCover(context, source, dx, dy, dw, dh) {
    const sw = Number(source?.videoWidth) || 0;
    const sh = Number(source?.videoHeight) || 0;
    if (!sw || !sh) return false;
    const scale = Math.max(dw / sw, dh / sh);
    const cropW = dw / scale;
    const cropH = dh / scale;
    context.drawImage(source, (sw - cropW) / 2, (sh - cropH) / 2, cropW, cropH, dx, dy, dw, dh);
    return true;
}

/**
 * 三分屏画布合成器：左侧坐席桌面，右上客户摄像头，右下坐席摄像头。
 *
 * 布局与基线完全一致（640×540 + 320×270 ×2），改尺寸会与已有存证的画面比例对不上。
 */
export class TripleScreenCompositor {
    /**
     * @param {{canvas:object, width?:number, height?:number,
     *          interval?:number, setInterval?:Function, clearInterval?:Function,
     *          labels?:object, timestamp?:()=>string}} options
     */
    constructor(options = {}) {
        this.canvas = options.canvas;
        this.width = options.width || COMPOSITE_WIDTH;
        this.height = options.height || COMPOSITE_HEIGHT;
        this.interval = options.interval ?? 100;
        this.labels = options.labels || { desktop: '坐席桌面', remote: '客户摄像头', local: '坐席摄像头' };
        this.timestamp = options.timestamp || (() => new Date().toLocaleString('zh-CN'));
        // 浏览器定时器必须以 Window 为接收者，不能把 compositor 实例作为 this 调用。
        this._setInterval = options.setInterval ?? globalThis.setInterval.bind(globalThis);
        this._clearInterval = options.clearInterval ?? globalThis.clearInterval.bind(globalThis);
        this._context = null;
        this._timer = null;
        /** 三路源：desktop / remote / local，都是具备 videoWidth 的对象或 video 元素。 */
        this.sources = { desktop: null, remote: null, local: null };
        if (this.canvas) {
            this.canvas.width = this.width;
            this.canvas.height = this.height;
        }
    }

    get context() {
        if (!this._context && this.canvas?.getContext) this._context = this.canvas.getContext('2d');
        return this._context;
    }

    setSource(key, source) {
        if (key in this.sources) this.sources[key] = source || null;
        return this.sources;
    }

    /** 画一帧。抽出来是为了让单测不必依赖 setInterval 就能验证布局。 */
    drawFrame() {
        const context = this.context;
        if (!context) return false;
        const { width, height } = this;
        context.fillStyle = '#101c2e';
        context.fillRect(0, 0, width, height);

        const desktopW = Math.round(width * 2 / 3);
        const sideW = width - desktopW;
        const sideH = Math.round(height / 2);

        if (ready(this.sources.desktop)) drawCover(context, this.sources.desktop, 0, 0, desktopW, height);
        if (ready(this.sources.remote)) drawCover(context, this.sources.remote, desktopW, 0, sideW, sideH);
        if (ready(this.sources.local)) drawCover(context, this.sources.local, desktopW, sideH, sideW, sideH);

        // 三条标签底衬：不画的话三路画面在深色背景下会糊成一团，存证时无法分辨谁是谁。
        context.fillStyle = '#101c2e';
        context.globalAlpha = 0.72;
        context.fillRect(0, 0, desktopW, 28);
        context.fillRect(desktopW, 0, sideW, 28);
        context.fillRect(desktopW, sideH, sideW, 28);
        context.globalAlpha = 1;

        context.fillStyle = '#fff';
        context.font = '14px Microsoft YaHei';
        context.fillText(this.labels.desktop, 12, 20);
        context.fillText(this.labels.remote, desktopW + 12, 20);
        context.fillText(this.labels.local, desktopW + 12, sideH + 20);

        context.font = '12px Microsoft YaHei';
        context.fillText(`人工见证 · ${this.timestamp()}`, 12, height - 15);
        return true;
    }

    /** 启动合成循环；已有循环时先停掉，避免两个 timer 叠加把 CPU 吃满。 */
    start() {
        this.stop();
        this.drawFrame();
        this._timer = this._setInterval(() => this.drawFrame(), this.interval);
        return this._timer;
    }

    stop() {
        if (this._timer != null) {
            this._clearInterval(this._timer);
            this._timer = null;
        }
        return true;
    }

    /**
     * 取合成流。帧率沿用基线的 10fps：三分屏是存证用途，
     * 更高的帧率只带来更大的落盘体积，不带来更多可辨识信息。
     */
    captureStream(frameRate = 10) {
        if (!this.canvas?.captureStream) return null;
        return this.canvas.captureStream(frameRate);
    }
}

const ready = source => Boolean(source && Number(source.readyState ?? 4) >= 2 && Number(source.videoWidth) > 0);

/**
 * 抓帧：返回 **Blob**（契约 §2.9.3）。
 *
 * @param {HTMLVideoElement|object} source 具备 videoWidth/videoHeight 的画面源
 * @param {{canvas?:object, type?:string, quality?:number, toBlob?:Function}} options
 * @returns {Promise<Blob|null>} 未收到画面时返回 **null**，绝不伪造占位图
 */
export async function capture(source, options = {}) {
    const width = Number(source?.videoWidth) || 0;
    const height = Number(source?.videoHeight) || 0;
    // readyState < 2 表示还没有可绘制的帧；此时硬画会拿到一张全黑图，比返回 null 更糟。
    if (!source || Number(source.readyState ?? 0) < 2 || !width || !height) return null;

    const canvas = options.canvas ?? createCanvas();
    if (!canvas) return null;
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext?.('2d');
    if (!context) return null;
    context.drawImage(source, 0, 0, width, height);

    const type = options.type || 'image/png';
    const quality = options.quality;
    const toBlob = options.toBlob ?? ((cb) => canvas.toBlob(cb, type, quality));

    return new Promise(resolve => {
        let settled = false;
        const done = blob => {
            if (settled) return;
            settled = true;
            // toBlob 在极端情况下会给 null，这里如实向上抛 null 而不是造一个空 Blob 顶替。
            resolve(blob && blob.size > 0 ? blob : null);
        };
        try {
            const result = toBlob(done);
            if (result && typeof result.then === 'function') result.then(done).catch(() => done(null));
        } catch (error) {
            done(null);
        }
    });
}

function createCanvas() {
    if (typeof document !== 'undefined' && document.createElement) return document.createElement('canvas');
    return null;
}

export default { drawCover, capture, TripleScreenCompositor, COMPOSITE_WIDTH, COMPOSITE_HEIGHT };
