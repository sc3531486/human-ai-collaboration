/**
 * 画面承载面（Surface）—— 控件内部把 MediaStream 与 `<video>` DOM 收在这一层。
 *
 * ★ 隔离目的：内核（controller / recorder / capture）**完全不碰 srcObject**，
 *   只通过 `attach(key, stream)` / `source(key)` 与画面源打交道。
 *   于是：
 *     - 内核可以直接在 node 里单测（注入 NullSurface 即可）；
 *     - 业务侧更不可能拿到 MediaStream（连内核都没有引用，只有视图层有）；
 *     - 换渲染层（Vue / 未来别的框架）时命令 API 一行不改。
 *
 * 视图层（stage.js 的 VideoStage）实现真身；单测与无 DOM 环境用 NullSurface。
 */

export const SURFACE_KEYS = Object.freeze(['remote', 'secondary', 'local', 'desktop']);

/** 无 DOM 环境的空实现：attach 只记账，source 返回 null。 */
export class NullSurface {
    constructor() {
        /** @type {Map<string, object>} */
        this.attached = new Map();
        this.canvas = null;
    }

    attach(key, stream) {
        if (!SURFACE_KEYS.includes(key)) throw new Error(`未知的画面槽位：${key}`);
        this.attached.set(key, stream || null);
        return stream || null;
    }

    detach(key) {
        this.attached.delete(key);
        return true;
    }

    source(key) { return this.attached.get(key) ?? null; }

    /** 供三分屏合成用的画布。NullSurface 下为 null，录制会退化为"不合成"。 */
    createCanvas() { return this.canvas; }

    setCanvas(canvas) { this.canvas = canvas; return canvas; }

    /** 抓帧用的可绘制源：View 返回真实 video 元素；这里返回 attach 进来的对象。 */
    drawable(key) { return this.source(key); }

    clear() {
        this.attached.clear();
        return true;
    }
}

/**
 * DOM 版承载面：由视图层构造，把流真正挂到 `<video>` 元素上。
 * ★ 这是整个控件里**唯一**出现 `srcObject` 的地方（另有 local.js 的桌面视频元素）。
 *   业务侧任何文件都不允许出现该字样。
 */
export class DomSurface extends NullSurface {
    /**
     * @param {{elements?:Record<string, HTMLVideoElement>, document?:object,
     *          createCanvas?:()=>HTMLCanvasElement}} options
     */
    constructor(options = {}) {
        super();
        this.document = options.document ?? globalThis.document ?? null;
        this.elements = { ...(options.elements || {}) };
        this._createCanvas = options.createCanvas ?? (() => this.document?.createElement?.('canvas') ?? null);
    }

    element(key) { return this.elements[key] ?? null; }

    bind(key, element) {
        this.elements[key] = element;
        // 绑上来时若已有流，立刻回放上去——复用本地流的场景就靠这一句把预览挂回去。
        const pending = this.attached.get(key);
        if (pending && element && element.srcObject !== pending) {
            element.srcObject = pending;
            element.play?.().catch(() => {});
        }
        return element;
    }

    attach(key, stream) {
        super.attach(key, stream);
        const element = this.element(key);
        if (element) {
            if (element.srcObject !== stream) element.srcObject = stream;
            if (stream) element.play?.().catch(() => {});
        }
        return stream || null;
    }

    detach(key) {
        const element = this.element(key);
        if (element) element.srcObject = null;
        return super.detach(key);
    }

    createCanvas() {
        if (!this.canvas) this.canvas = this._createCanvas();
        return this.canvas;
    }

    /** 抓帧必须拿真实元素：只有它有 videoWidth / readyState。 */
    drawable(key) { return this.element(key) ?? this.source(key); }

    clear() {
        SURFACE_KEYS.forEach(key => this.detach(key));
        return super.clear();
    }
}

export default NullSurface;
