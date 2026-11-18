/*
 * 冒烟/录制测试共用替身（纯 JS，不触碰真实媒体设备）。
 * 抽公共：widget-smoke 与 recording 两套 node 测试复用同一组夹具，
 * 避免 fake stream / 传输 / MediaRecorder / 内存 IDB 两处维护。
 * 注意：本文件不匹配 *.test.mjs，不会被 node --test 当作测试执行。
 */
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** 控件产品产物（lead 指定：联调用 dist 产物，不用源码）。 */
export const WIDGET_DIST = new URL('../../../video-widget/dist/vb-video.esm.js', import.meta.url);

/** 载入控件产物；缺失时给出可执行的修复提示（而非晦涩的模块错误）。 */
export async function loadWidgetProduct() {
    const path = fileURLToPath(WIDGET_DIST);
    assert.ok(
        existsSync(path),
        `缺少控件产物 ${path}——请先在 video-widget/ 执行 npm run build（lead 指定用产品产物联调）`,
    );
    return import(WIDGET_DIST);
}

/** MediaStream 替身：可断言 track 是否被 hangup 停掉（§8.4-5）。 */
export function makeTrack(kind) {
    return {
        kind,
        readyState: 'live',
        enabled: true,
        stop() {
            this.readyState = 'ended';
        },
    };
}

export function makeStream(kinds) {
    const tracks = kinds.map(makeTrack);
    return {
        getTracks: () => tracks,
        getVideoTracks: () => tracks.filter((t) => t.kind === 'video'),
        getAudioTracks: () => tracks.filter((t) => t.kind === 'audio'),
    };
}

/**
 * 传输替身：不建任何网络链路。publish 即 connected，subscribe 直接派发 remote
 * ——验证的正是适配层参数翻译与控件 wire(state/local/remote) 通路。
 */
export class SmokeTransport {
    constructor(options = {}) {
        this.name = options.name || 'smoke';
        this.role = options.role || 'agent';
        this._state = 'idle';
        this._listeners = new Map();
        this.counters = { publish: 0, subscribe: 0, hangup: 0 };
    }

    on(event, handler) {
        if (!this._listeners.has(event)) this._listeners.set(event, new Set());
        this._listeners.get(event).add(handler);
        return () => this._listeners.get(event)?.delete(handler);
    }

    emit(event, payload) {
        for (const fn of Array.from(this._listeners.get(event) || [])) fn(payload);
    }

    setState(next) {
        const previous = this._state;
        this._state = next;
        this.emit('state', { state: next, previous, transport: this.name });
    }

    async connect() {
        this.setState('connected');
        return this;
    }

    async publish(stream) {
        this.counters.publish += 1;
        if (stream) this.emit('local', { stream });
        this.setState('connected');
        return stream;
    }

    async subscribe(peerId) {
        this.counters.subscribe += 1;
        const remote = makeStream(['video', 'audio']);
        this.emit('remote', { stream: remote, peerId });
        return remote;
    }

    async hangup() {
        this.counters.hangup += 1;
        this.emit('peer-left', { peerId: null });
        this.setState('closed');
        return true;
    }

    getStats() {
        return { name: this.name, state: this._state, ...this.counters };
    }

    async destroy() {
        await this.hangup();
        this._listeners.clear();
        return true;
    }
}

/** MediaRecorder 替身：stop 时交出一片非空 Blob，随后 onstop（封存等待点）。 */
export class FakeMediaRecorder {
    static isTypeSupported() {
        return true;
    }

    constructor(stream, opts = {}) {
        this.stream = stream;
        this.mimeType = opts.mimeType || 'video/webm';
        this.state = 'inactive';
        this.ondataavailable = null;
        this.onstop = null;
        this.onerror = null;
    }

    start() {
        this.state = 'recording';
    }

    stop() {
        if (this.state === 'inactive') return;
        this.state = 'inactive';
        queueMicrotask(() => {
            try {
                this.ondataavailable?.({ data: new Blob(['fake-frame-bytes'], { type: this.mimeType }) });
                this.onstop?.();
            } catch (error) {
                this.onerror?.(error);
            }
        });
    }
}

/** 内存版 indexedDB：operation() 依赖 IDBRequest.result 与 transaction.oncomplete。 */
export function makeFakeIndexedDB() {
    const stores = new Map();
    const rowsOf = (name) => {
        if (!stores.has(name)) stores.set(name, new Map());
        return stores.get(name);
    };
    return {
        open(name) {
            const request = {};
            queueMicrotask(() => {
                const db = {
                    objectStoreNames: { contains: (storeName) => stores.has(`${name}:${storeName}`) },
                    createObjectStore(storeName) {
                        rowsOf(`${name}:${storeName}`);
                        return {};
                    },
                    transaction(storeName) {
                        const rows = rowsOf(`${name}:${storeName}`);
                        const store = {
                            put(row) {
                                rows.set(row.key, row);
                                return { result: row.key };
                            },
                            getAll() {
                                return { result: [...rows.values()].map((row) => ({ ...row })) };
                            },
                            delete(key) {
                                rows.delete(key);
                                return { result: undefined };
                            },
                            clear() {
                                rows.clear();
                                return { result: undefined };
                            },
                        };
                        const tx = { objectStore: () => store };
                        queueMicrotask(() => tx.oncomplete && tx.oncomplete());
                        return tx;
                    },
                };
                request.result = db;
                request.onupgradeneeded && request.onupgradeneeded();
                request.onsuccess && request.onsuccess();
            });
            return request;
        },
    };
}

/** 录制链全局替身：indexedDB / MediaRecorder / fetch。receipt 可变引用以逐段切换回执。 */
export function installRecordGlobals(initialReceipt) {
    const holder = { receipt: initialReceipt };
    globalThis.indexedDB = makeFakeIndexedDB();
    globalThis.MediaRecorder = FakeMediaRecorder;
    globalThis.fetch = async () => ({
        ok: true,
        status: 200,
        json: async () => ({ ...holder.receipt }),
    });
    return holder;
}
