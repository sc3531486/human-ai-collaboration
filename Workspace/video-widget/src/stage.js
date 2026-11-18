/**
 * VideoStage —— 原生 JS 视图层（契约 §2.9：**零框架依赖**，不引 Vue/React）。
 *
 * 职责就三件事：
 *  1. 把 DOM（视频窗 / 占位 / 徽标 / 工具栏 / 错误条）渲染进宿主给的容器；
 *  2. 把 controller 的 state 变成可见的 class / text / style（唯一数据源是 `getState()`）；
 *  3. 把 DOM 事件（拖拽、工具栏点击）翻译回命令 API（moveTo / capture / toggle / hangup）。
 *
 * ★ 与内核的边界：视图层是**唯一**碰 `<video>` DOM 的地方——通过 DomSurface 挂流，
 *   内核只见 surface 接口（§6.4 MediaTransport / surface 抽象）。挂载时把 DomSurface
 *   经 `controller.useSurface()` 换进去；销毁时换回 NullSurface 并清掉 srcObject（§8.4-5）。
 *
 * 布局沿用 `layout.js` 的同一口径：overlay 用归一化→像素换算（拖拽回写归一化值），
 * dock 退化为 order + heightRatio 参与宿主流式布局（§2.9.2）。
 *
 * 样式在 `styles.css`（构建后为 dist/vb-video.css），全部以 `.vb-` 前缀限定，不污染宿主。
 */

import { clamp, normalize } from './layout.js';
import { DomSurface, NullSurface } from './media/surface.js';

/** 默认图标（内联 SVG，零依赖零字体）。宿主可用 options.icons 逐个替换为文本或自定义 SVG。 */
export const DEFAULT_ICONS = Object.freeze({
    capture: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="13" r="3.5"/><path d="M4 8.5h3l1.5-2h7L17 8.5h3v10H4z"/></svg>',
    audio: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg>',
    hangup: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 14.5c5-5 12-5 17 0l-2.5 2.5-3.5-1.5v-2.2a10.5 10.5 0 0 0-5 0v2.2L6 17z"/></svg>',
    drag: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 4v16M4 12h16M9 7l3-3 3 3M9 17l3 3 3-3M7 9l-3 3 3 3M17 9l3 3-3 3"/></svg>',
    unlock: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M7 10V7a5 5 0 0 1 9.5-2M6 10h12v9H6z"/><circle cx="12" cy="14.5" r="1.4" fill="currentColor" stroke="none"/></svg>'
});

const noop = () => {};

export class VideoStage {
    /**
     * @param {{controller:object, toolbar?:boolean, showLocalPreview?:boolean,
     *          zIndex?:number, icons?:Record<string,string>, placeholder?:string,
     *          onCapture?:(blob:Blob|null)=>void, onHangup?:()=>void}} options
     */
    constructor(options = {}) {
        if (!options.controller) throw new Error('VideoStage 需要 controller（命令 API 实例）');
        this.controller = options.controller;
        this.options = {
            toolbar: options.toolbar !== false,
            showLocalPreview: options.showLocalPreview !== false,
            zIndex: options.zIndex ?? 2000,
            icons: { ...DEFAULT_ICONS, ...(options.icons || {}) },
            placeholder: options.placeholder ?? '等待客户接入…',
            unlockText: options.unlockText ?? '开启画面与声音',
            onCapture: options.onCapture ?? noop,
            onHangup: options.onHangup ?? noop,
            onHangupRequest: options.onHangupRequest ?? null
        };

        this.root = null;
        this.surface = null;
        this.box = null;
        this.viewport = { width: 0, height: 0 };
        this.dragging = false;
        this.showUnlock = false;
        this._hadRemote = false;
        this._listeners = new Map();
        this._offState = null;
        this._observer = null;
        this._restoreParentPosition = null;
        this._els = {};
        this._drag = null;
        this._handlers = {};
    }

    // ---------- 轻量事件（供宿主接 capture / hangup / move / error） ----------

    on(event, handler) {
        if (typeof handler !== 'function') return noop;
        if (!this._listeners.has(event)) this._listeners.set(event, new Set());
        this._listeners.get(event).add(handler);
        return () => this.off(event, handler);
    }

    off(event, handler) { this._listeners.get(event)?.delete(handler); }

    emit(event, payload) {
        (this._listeners.get(event) || []).forEach(handler => {
            try { handler(payload); } catch (error) { console.error(`[video] stage ${event} 监听器异常`, error); }
        });
    }

    // ---------- 挂载 / 销毁 ----------

    /**
     * 把控件渲染进宿主容器。只管 DOM 落位，不申请任何媒体权限（§2.9.1）。
     * @param {Element} container
     */
    mount(container) {
        if (this.root) return this;
        if (!container || typeof container.appendChild !== 'function') {
            throw new Error('mount(container) 需要一个容器元素');
        }
        const doc = container.ownerDocument ?? globalThis.document;
        if (!doc) throw new Error('VideoStage 只能在浏览器环境挂载（node 里请直接用 controller + NullSurface）');

        this.root = doc.createElement('div');
        this.root.className = 'vb-video-stage';
        this.root.style.zIndex = String(this.options.zIndex);
        this.root.setAttribute('data-vb-stage', '');
        this._build(doc);
        container.appendChild(this.root);

        // 绝对定位要有定位上下文：宿主没给 position 时补上，销毁时还原。
        try {
            const computed = doc.defaultView?.getComputedStyle(container);
            if (!computed || computed.position === 'static') {
                this._restoreParentPosition = container.style.position || '';
                container.style.position = 'relative';
            }
        } catch (error) { /* getComputedStyle 不可用时按宿主给的来 */ }

        // 把 DomSurface 换进内核：远端/本地两个 <video> 从现在起是画面的唯一去处。
        this.surface = new DomSurface({ document: doc });
        this.surface.bind('remote', this._els.remote);
        this.surface.bind('secondary', this._els.secondary);
        this.surface.bind('local', this._els.local);
        this.controller.useSurface?.(this.surface);

        this._offState = this.controller.on('state', state => {
            this.render(state);
            // open({layout/pixel/mode}) 与 moveTo 之后按新布局重算（measure 幂等且不回写 state）。
            this.measure();
        });

        // 容器尺寸变化（分屏/侧栏开合/分辨率切换）→ 重新按归一化换算。
        const view = doc.defaultView;
        if (typeof view?.ResizeObserver === 'function') {
            this._observer = new view.ResizeObserver(() => this.measure());
            this._observer.observe(container);
        }

        this._bindPointer();
        this.render(this.controller.getState());
        this.measure();
        return this;
    }

    /** 卸载并释放：退订、断观察、清 srcObject（§8.4-5）、移除 DOM、还原宿主样式。 */
    destroy() {
        if (!this.root) return false;
        this._offState?.();
        this._offState = null;
        this._observer?.disconnect?.();
        this._observer = null;

        Object.entries(this._handlers).forEach(([type, fn]) => {
            this.root?.removeEventListener(type, fn);
        });
        this._handlers = {};

        if (this.surface) {
            // 内核还挂着这个承载面就先换回 NullSurface，再清掉 video.srcObject。
            if (this.controller.surface === this.surface) this.controller.useSurface(new NullSurface());
            this.surface.clear();
            this.surface = null;
        }

        const parent = this.root.parentElement;
        this.root.remove();
        this.root = null;
        if (parent && this._restoreParentPosition !== null) {
            parent.style.position = this._restoreParentPosition;
            this._restoreParentPosition = null;
        }
        this._listeners.clear();
        return true;
    }

    // ---------- DOM 构建 ----------

    _build(doc) {
        const els = this._els = {};
        const make = (tag, className, text) => {
            const el = doc.createElement(tag);
            if (className) el.className = className;
            if (text != null) el.textContent = text;
            return el;
        };
        const iconSpan = (name) => {
            const span = make('span', 'vb-stage__icon');
            const svg = this.options.icons[name];
            if (typeof svg === 'string' && svg.trimStart().startsWith('<')) span.innerHTML = svg;
            else span.textContent = svg ?? '';
            span.setAttribute('aria-hidden', 'true');
            return span;
        };

        els.remote = make('video', 'vb-stage__video vb-stage__remote');
        els.remote.playsInline = true;
        els.secondary = make('video', 'vb-stage__video vb-stage__secondary');
        els.secondary.playsInline = true;els.secondary.hidden = true;

        els.placeholder = make('div', 'vb-stage__placeholder');
        const phIcon = make('span', 'vb-stage__ph-icon');
        phIcon.innerHTML = '<svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="6" width="13" height="12" rx="2"/><path d="M16 10.5l5-3v9l-5-3z"/></svg>';
        els.phText = make('p', 'vb-stage__ph-text', this.options.placeholder);
        els.placeholder.append(phIcon, els.phText);

        els.local = make('video', 'vb-stage__video vb-stage__local');
        els.local.muted = true;
        els.local.playsInline = true;

        els.badge = make('div', 'vb-stage__badge');
        els.badgeDot = make('i', 'vb-stage__dot');
        els.badgeText = make('span', null, '');
        els.badge.append(els.badgeDot, els.badgeText);

        els.rec = make('div', 'vb-stage__rec vb-stage__rec--on', 'REC');

        els.unlock = make('button', 'vb-stage__unlock');
        els.unlock.type = 'button';
        els.unlock.append(iconSpan('unlock'), make('span', null, this.options.unlockText));

        els.toolbar = make('div', 'vb-stage__toolbar');
        els.toolbar.setAttribute('role', 'toolbar');
        els.btnCapture = this._makeButton('capture', '抓拍');
        els.btnAudio = this._makeButton('audio', '静音');
        els.btnHangup = this._makeButton('hangup', '挂断');
        els.btnDrag = this._makeButton('drag', '拖动窗口');
        els.btnDrag.dataset.action = 'drag';
        els.toolbar.append(els.btnCapture, els.btnAudio, els.btnHangup, els.btnDrag);

        els.error = make('p', 'vb-stage__error');

        this.root.append(els.remote, els.placeholder, els.local, els.secondary, els.badge, els.rec, els.unlock, els.toolbar, els.error);

        // ---- 交互 ----
        this._handlers.action = (event) => {
            if (event.target === els.secondary) {
                this.controller.swapRemote?.();
                return;
            }
            const button = event.target?.closest?.('button[data-action]');
            if (!button || !this.root?.contains(button)) return;
            if (button.dataset.action === 'drag') return; // 拖拽由 pointer 处理
            this._runAction(button.dataset.action);
        };
        this.root.addEventListener('click', this._handlers.action);

        this._handlers.unlock = () => {
            this._els.remote.muted = false;
            this.showUnlock = false;
            this._els.remote.play?.()?.catch?.(() => { this.showUnlock = true; });
            this.render(this.controller.getState());
        };
        els.unlock.addEventListener('click', this._handlers.unlock);
    }

    _makeButton(action, label) {
        const doc = this.root.ownerDocument;
        const button = doc.createElement('button');
        button.type = 'button';
        button.className = `vb-stage__btn vb-stage__btn--${action}`;
        button.dataset.action = action;
        button.title = label;
        button.setAttribute('aria-label', label);
        button.append(this._icon(action));
        return button;
    }

    _icon(name) {
        const span = this.root.ownerDocument.createElement('span');
        span.className = 'vb-stage__icon';
        const svg = this.options.icons[name];
        if (typeof svg === 'string' && svg.trimStart().startsWith('<')) span.innerHTML = svg;
        else span.textContent = svg ?? '';
        span.setAttribute('aria-hidden', 'true');
        return span;
    }

    // ---------- 渲染（state → DOM） ----------

    /**
     * state 的唯一消费者。幂等：重复渲染同一 state 不产生副作用。
     * @param {object} state controller.getState() 的快照
     */
    render(state) {
        if (!this.root) return;
        const els = this._els;
        const dock = state.mode === 'dock';

        this.root.classList.toggle('vb-video-stage--dock', dock);
        this.root.classList.toggle('vb-video-stage--overlay', !dock);
        this.root.classList.toggle('is-draggable', Boolean(state.draggable) && !dock);
        this.root.classList.toggle('is-dragging', this.dragging);

        // 占位：远端真正到达才收起（和 hasRemote 同一判据）。
        els.placeholder.hidden = Boolean(state.hasRemote);
        const hadRemote = this._hadRemote;
        this._hadRemote = Boolean(state.hasRemote);
        if (!hadRemote && state.hasRemote) this.ensurePlaying(); // 自动播放被拦 → 出解锁按钮
        if (hadRemote && !state.hasRemote) this.showUnlock = false;

        // 本地小窗：宿主关掉或本地流没来就不画黑框。
        els.local.hidden = !(this.options.showLocalPreview && state.hasLocal);
        els.secondary.hidden = !state.hasSecondaryRemote;

        // 状态徽标：通话中 / 待接入 / 演示画面。
        els.badge.hidden = !(state.mounted || state.call);
        els.badgeText.textContent = state.usingMockStream ? '演示画面'
            : state.call ? '通话中'
                : '待接入';
        els.badge.classList.toggle('vb-badge--live', state.call && !state.usingMockStream);
        els.badge.classList.toggle('vb-badge--mock', Boolean(state.usingMockStream));

        els.rec.hidden = !state.recording;

        els.unlock.hidden = !(this.showUnlock && state.hasRemote);

        els.toolbar.hidden = !(this.options.toolbar && state.mounted);

        // 静音按钮：audioEnabled=false 表示本地麦已关。
        els.btnAudio.classList.toggle('is-on', state.audioEnabled === false);
        els.btnAudio.setAttribute('aria-pressed', String(state.audioEnabled === false));

        if (state.error) {
            els.error.hidden = false;
            els.error.textContent = state.error;
        } else {
            els.error.hidden = true;
            els.error.textContent = '';
        }
    }

    // ---------- 布局（layout.js 同一口径） ----------

    /** 按容器尺寸与 state（mode/layout/pixel）换算并落 style。宿主 resize 时自动重算。 */
    measure() {
        if (!this.root) return;
        const parent = this.root.parentElement;
        const state = this.controller.getState();
        this.viewport = { width: parent?.clientWidth ?? 0, height: parent?.clientHeight ?? 0 };

        if (state.mode === 'dock') {
            // dock：横向交给宿主流式布局，这里只回写顺序与高度（§2.9.2）。
            const dock = this.controller.resolveBox(this.viewport);
            this.root.style.removeProperty('left');
            this.root.style.removeProperty('top');
            this.root.style.removeProperty('width');
            this.root.style.order = String(dock.order ?? 0);
            this.root.style.height = dock.height ? `${dock.height}px` : '';
            return;
        }

        if (!this.dragging) this.box = this.controller.resolveBox(this.viewport);
        this.applyBox();
    }

    applyBox() {
        if (!this.root || !this.box) return;
        const { left, top, width, height } = this.box;
        this.root.style.left = `${Math.round(left)}px`;
        this.root.style.top = `${Math.round(top)}px`;
        this.root.style.width = `${Math.round(width)}px`;
        this.root.style.height = `${Math.round(height)}px`;
    }

    // ---------- 拖拽（回写归一化值，§2.9.2） ----------

    _bindPointer() {
        this._handlers.pointerdown = (event) => {
            const state = this.controller.getState();
            if (!state.draggable || state.mode === 'dock') return;
            if (event.button !== 0) return;
            if (event.target?.closest?.('button')) return; // 工具栏按钮不引发拖拽
            event.preventDefault();
            this.dragging = true;
            this._drag = {
                id: event.pointerId,
                x: event.clientX, y: event.clientY,
                box: { ...(this.box || this.controller.resolveBox(this.viewport)) }
            };
            try { this.root.setPointerCapture?.(event.pointerId); } catch (error) { /* 忽略 */ }
            this.root.classList.add('is-dragging');
        };

        this._handlers.pointermove = (event) => {
            if (!this.dragging || !this._drag || event.pointerId !== this._drag.id) return;
            const next = {
                ...this._drag.box,
                left: this._drag.box.left + (event.clientX - this._drag.x),
                top: this._drag.box.top + (event.clientY - this._drag.y)
            };
            // 拖出容器也要钳回来：任何配置/手势都不允许把窗口推出视口。
            this.box = clamp(next, this.viewport);
            this.applyBox();
        };

        this._handlers.pointerup = (event) => {
            if (!this.dragging || !this._drag || event.pointerId !== this._drag.id) return;
            this.dragging = false;
            this._drag = null;
            this.root.classList.remove('is-dragging');
            try { this.root.releasePointerCapture?.(event.pointerId); } catch (error) { /* 忽略 */ }
            // ★ 回写归一化值并持久化（controller.moveTo 内部完成），换分辨率仍在相对位置。
            const saved = this.controller.moveTo({ ...this.box }, this.viewport);
            this.emit('move', saved);
            this.measure();
        };

        this.root.addEventListener('pointerdown', this._handlers.pointerdown);
        this.root.addEventListener('pointermove', this._handlers.pointermove);
        this.root.addEventListener('pointerup', this._handlers.pointerup);
        this.root.addEventListener('pointercancel', this._handlers.pointerup);
    }

    // ---------- 工具栏 → 命令 API ----------

    async _runAction(action) {
        try {
            if (action === 'capture') {
                const blob = await this.controller.capture('remote');
                // ★ 无画面时是 null：不伪造占位图，交给宿主决定提示。
                this.options.onCapture(blob);
                this.emit('capture', blob);
            } else if (action === 'audio') {
                await this.controller.toggle('audio');
                this.render(this.controller.getState());
            } else if (action === 'hangup') {
                if (this.options.onHangupRequest) await this.options.onHangupRequest();
                else await this.controller.hangup();
                this.options.onHangup();
                this.emit('hangup');
            }
        } catch (error) {
            this.emit('error', error);
            const state = this.controller.getState();
            if (state.error) this.render(state);
        }
    }

    // ---------- 自动播放兜底 ----------

    /** 宿主策略拦截自动播放时出「开启画面与声音」按钮，而不是黑屏。 */
    ensurePlaying() {
        if (!this._els.remote) return;
        const promise = this._els.remote.play?.();
        if (promise && typeof promise.catch === 'function') {
            promise.catch(() => {
                this.showUnlock = true;
                this.render(this.controller.getState());
            });
        }
    }
}

export default VideoStage;
