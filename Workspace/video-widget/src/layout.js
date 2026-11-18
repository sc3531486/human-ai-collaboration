/**
 * 视频控件位置配置解析：比例为主 + 像素可选 + 边界 clamp（契约 §2.9.2）。
 *
 * ★ 纯函数、零 DOM 依赖、零框架依赖：本文件是内核的一部分，直接在 node 里单测。
 *   视图层（stage.js）与业务侧都用同一份口径换算，避免两处算法漂移。
 *
 * 三条来源判据（逐条对应契约）：
 *  1. 比例为主：`layout` 里的 left/top/width/height 是相对容器的归一化 0~1 值，
 *     天然适配 1366×768 ~ 2560×1440，不需要为每种分辨率写断点。
 *  2. 像素可选：`pixel` 字段存在时覆盖对应维度的比例换算结果（需要精确对齐时用）。
 *  3. 边界 clamp：换算后必须钳制在容器内——任何配置都不允许把窗口推出视口。
 *
 * 拖拽落位回写的是**归一化值**而不是像素（normalize），换分辨率后仍在相对位置。
 *
 * @typedef {{left?:number, top?:number, width?:number, height?:number, right?:number, bottom?:number}} NormalizedLayout
 * @typedef {{left?:number, top?:number, width?:number, height?:number}} PixelOverride
 * @typedef {{width:number, height:number}} Viewport
 * @typedef {{left:number, top:number, width:number, height:number}} LayoutRect
 * @typedef {{order:number, heightRatio:number, height:number}} DockLayout
 */

/** overlay 模式默认布局：右上角客户画面窗（对齐 navis-web 坐席工作台的习惯位置）。 */
export const DEFAULT_LAYOUT = { left: 0.72, top: 0.05, width: 0.26, height: 0.3 };

/** dock 模式默认布局：流式布局里退化为「顺序 + 高度占比」。 */
export const DEFAULT_DOCK_LAYOUT = { order: 0, heightRatio: 0.3, height: 0 };

export const STORAGE_KEY = 'bank.video.layout.v1';

const finite = (value, fallback = 0) => {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
};

/** 归一化值钳到 0~1：配置里出现越界比例时按边界处理，不让窗口漂到容器外。 */
export const ratio = (value) => Math.min(Math.max(finite(value), 0), 1);

const round4 = (value) => Number(Math.min(Math.max(finite(value), 0), 1).toFixed(4));

/**
 * 边界 clamp：把像素盒子钳制在容器内。
 *
 * 尺寸大于容器时优先保左上对齐并截断到容器尺寸——若按比例缩放，
 * 用户拖到边上再拖回来会「缩水」，且绝对定位窗口整体漂移到不可见区域更难排查。
 */
export function clamp(box, viewport = { width: 0, height: 0 }) {
    const width = Math.max(0, finite(viewport.width));
    const height = Math.max(0, finite(viewport.height));
    const size = (value, total) => Math.min(Math.max(finite(value), 0), total);
    const boxWidth = size(box.width, width);
    const boxHeight = size(box.height, height);
    const maxLeft = Math.max(0, width - boxWidth);
    const maxTop = Math.max(0, height - boxHeight);
    return {
        left: Math.min(Math.max(finite(box.left), 0), maxLeft),
        top: Math.min(Math.max(finite(box.top), 0), maxTop),
        width: boxWidth,
        height: boxHeight
    };
}

/**
 * 把归一化布局 + 可选像素覆盖换算成容器内的像素盒子。
 *
 * ★ pixel 覆盖同样要过 clamp：像素场是可选的精确对齐手段，不是绕过边界约束的口子。
 */
export function resolveLayout(layout = {}, viewport = { width: 0, height: 0 }, pixel = {}) {
    const width = Math.max(0, finite(viewport.width));
    const height = Math.max(0, finite(viewport.height));

    // 像素优先：pixel 字段存在时直接采用，不再走比例换算。
    const boxWidth = pixel.width != null ? finite(pixel.width) : ratio(layout.width) * width;
    const boxHeight = pixel.height != null ? finite(pixel.height) : ratio(layout.height) * height;

    // right / bottom 是 left / top 的反向写法：给了 right 就没必要再算 left。
    const rawLeft = pixel.left != null ? finite(pixel.left)
        : layout.left != null ? ratio(layout.left) * width
            : layout.right != null ? width - boxWidth - ratio(layout.right) * width
                : 0;
    const rawTop = pixel.top != null ? finite(pixel.top)
        : layout.top != null ? ratio(layout.top) * height
            : layout.bottom != null ? height - boxHeight - ratio(layout.bottom) * height
                : 0;

    return clamp({ left: rawLeft, top: rawTop, width: boxWidth, height: boxHeight }, { width, height });
}

/**
 * dock 模式（参与流式布局）的布局退化：`{ order, heightRatio }`。
 *
 * dock 只关心「排第几」和「占多高」，横向由父容器流式决定，
 * 因此这里不返回 left/top/width——返回了也会被 flex 布局忽略。
 */
export function resolveDock(layout = {}, viewport = { width: 0, height: 0 }) {
    const height = Math.max(0, finite(viewport.height));
    const heightRatio = layout.heightRatio != null
        ? ratio(layout.heightRatio)
        : ratio(DEFAULT_DOCK_LAYOUT.heightRatio);
    return {
        order: Math.max(0, Math.round(finite(layout.order, DEFAULT_DOCK_LAYOUT.order))),
        heightRatio,
        height: Math.round(heightRatio * height)
    };
}

/** 按模式分派：dock 走退化布局，其余走 overlay 的像素换算。 */
export function resolve(mode, layout = {}, viewport = { width: 0, height: 0 }, pixel = {}) {
    return mode === 'dock' ? resolveDock(layout, viewport) : resolveLayout(layout, viewport, pixel);
}

/**
 * 拖动落位回写：存归一化值而非像素。
 * 坐席可能今天 1366×768、明天换 2560×1440，存像素会让窗口跑到一个完全不对的位置。
 */
export function normalize(box, viewport = { width: 0, height: 0 }) {
    const width = finite(viewport.width);
    const height = finite(viewport.height);
    return {
        left: width > 0 ? round4(box.left / width) : 0,
        top: height > 0 ? round4(box.top / height) : 0,
        width: width > 0 ? round4(box.width / width) : 0,
        height: height > 0 ? round4(box.height / height) : 0
    };
}

function safeSession() {
    try {
        return globalThis.sessionStorage ?? null;
    } catch (error) {
        return null;
    }
}

/** 落位持久化。隐私模式下 setItem 会抛，这里退化为「不持久化」而不是让拖拽报错。 */
export function persistLayout(layout, options = {}) {
    const storage = options.storage ?? safeSession();
    const key = options.key ?? STORAGE_KEY;
    try {
        storage?.setItem(key, JSON.stringify(layout));
    } catch (error) {
        /* 隐私模式下退化为不持久化 */
    }
    return layout;
}

/** 读回落位；无记录或解析失败时返回 fallback 的副本，绝不返回 null 让调用方自己去判空。 */
export function restoreLayout(fallback = {}, options = {}) {
    const storage = options.storage ?? safeSession();
    const key = options.key ?? STORAGE_KEY;
    try {
        const raw = storage?.getItem(key);
        return raw ? { ...fallback, ...JSON.parse(raw) } : { ...fallback };
    } catch (error) {
        return { ...fallback };
    }
}

/** 多个布局来源合并：后写的覆盖先写的，undefined/null 不覆盖。 */
export function mergeLayout(base = {}, patch = {}) {
    const merged = { ...base };
    Object.entries(patch || {}).forEach(([key, value]) => {
        if (value !== undefined && value !== null) merged[key] = value;
    });
    return merged;
}

export default {
    DEFAULT_LAYOUT,
    DEFAULT_DOCK_LAYOUT,
    STORAGE_KEY,
    ratio,
    clamp,
    resolve,
    resolveLayout,
    resolveDock,
    normalize,
    persistLayout,
    restoreLayout,
    mergeLayout
};
