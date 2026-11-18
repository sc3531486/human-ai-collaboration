/**
 * 单测替身：假 DOM / 假 IDB / 假 MediaRecorder / 假流。
 *
 * 存在的理由：内核被要求"纯 JS、能在 node 里跑"，那依赖就必须可注入。
 * 这些替身只实现被测路径真正用到的那几个方法，不为"像真的"而堆代码。
 */

/** 最小 IndexedDB 替身：支持 open / transaction / objectStore 的 put / getAll / delete / clear。 */
export class FakeIndexedDB {
    constructor() { this.stores = new Map(); }

    open() {
        const request = {};
        const db = {
            objectStoreNames: {
                contains: name => this.stores.has(name)
            },
            createObjectStore: name => {
                if (!this.stores.has(name)) this.stores.set(name, new Map());
                return { name };
            },
            transaction: name => {
                if (!this.stores.has(name)) this.stores.set(name, new Map());
                const store = this.stores.get(name);
                const transaction = { __rows: store };
                transaction.objectStore = () => ({
                    put: row => { store.set(row.key, row); return { result: row }; },
                    getAll: () => ({ result: [...store.values()] }),
                    delete: key => { store.delete(key); return { result: undefined }; },
                    clear: () => { store.clear(); return { result: undefined }; }
                });
                queueMicrotask(() => transaction.oncomplete?.());
                return transaction;
            }
        };
        request.result = db;
        queueMicrotask(() => request.onupgradeneeded?.());
        queueMicrotask(() => request.onsuccess?.());
        return request;
    }
}

/** 假 Blob：node 22 有原生 Blob，但 size 语义要能被测试显式指定（含 0 字节）。 */
export class FakeBlob {
    constructor(parts = [], options = {}) {
        this.parts = parts;
        this.type = options.type || '';
        this.size = parts.reduce((sum, part) => sum + String(part ?? '').length, 0);
    }
    arrayBuffer() { return Promise.resolve(new ArrayBuffer(this.size)); }
}

/** 假 MediaRecorder：可控地吐出若干分片（含空分片）。 */
export class FakeMediaRecorder {
    static supported = new Set(['video/webm;codecs=vp8,opus', 'video/webm']);
    static isTypeSupported(type) { return FakeMediaRecorder.supported.has(type); }

    constructor(stream, options = {}) {
        this.stream = stream;
        this.mimeType = options.mimeType || 'video/webm';
        this.state = 'inactive';
        this.ondataavailable = null;
        this.onstop = null;
        this.onerror = null;
        FakeMediaRecorder.instances.push(this);
    }

    start() { this.state = 'recording'; return this; }

    /** 供测试主动喂一片；size=0 即空分片。 */
    feed(size) {
        const blob = new FakeBlob(['x'.repeat(size)], { type: this.mimeType });
        this.ondataavailable?.({ data: blob });
        return blob;
    }

    stop() {
        if (this.state !== 'recording') return;
        this.state = 'inactive';
        queueMicrotask(() => this.onstop?.());
    }
}
FakeMediaRecorder.instances = [];

/** 假 MediaStream：只需要 getTracks / getAudioTracks / getVideoTracks。 */
export function makeStream(kinds = ['video', 'audio'], id = 'stream-1') {
    const tracks = kinds.map(kind => ({
        kind,
        enabled: true,
        readyState: 'live',
        id: `${id}-${kind}`,
        stop() { this.readyState = 'ended'; }
    }));
    return {
        id,
        getTracks: () => tracks,
        getAudioTracks: () => tracks.filter(track => track.kind === 'audio'),
        getVideoTracks: () => tracks.filter(track => track.kind === 'video')
    };
}

/** 假 canvas + 2d context：记录 drawImage 的参数，供断言 drawCover 的裁切结果。 */
export function makeCanvas(width = 960, height = 540) {
    const calls = [];
    const context = {
        fillStyle: '', globalAlpha: 1, font: '',
        fillRect: (...args) => calls.push({ op: 'fillRect', args }),
        fillText: (...args) => calls.push({ op: 'fillText', args }),
        beginPath: () => {}, arc: () => {}, fill: () => {},
        drawImage: (...args) => calls.push({ op: 'drawImage', args })
    };
    const canvas = {
        width, height, calls,
        getContext: () => context,
        captureStream: () => ({ getTracks: () => [], addTrack: () => {} })
    };
    return canvas;
}

/** 假 video 元素（画面源）：具备 readyState / videoWidth / videoHeight。 */
export function makeVideoSource(width = 1280, height = 720, readyState = 4) {
    return { readyState, videoWidth: width, videoHeight: height, srcObject: null, play: () => Promise.resolve() };
}

/** 内存版 sessionStorage，避免单测依赖宿主环境。 */
export class MemoryStorage {
    constructor() { this.map = new Map(); }
    getItem(key) { return this.map.has(key) ? this.map.get(key) : null; }
    setItem(key, value) { this.map.set(key, String(value)); }
    removeItem(key) { this.map.delete(key); }
}

/** 收集事件订阅：用于断言状态机派发顺序。 */
export function makeRecorder() {
    const events = [];
    return {
        events,
        push: name => (payload) => events.push({ name, payload }),
        names: () => events.map(item => item.name)
    };
}

export default {
    FakeIndexedDB, FakeBlob, FakeMediaRecorder, makeStream, makeCanvas,
    makeVideoSource, MemoryStorage, makeRecorder
};
