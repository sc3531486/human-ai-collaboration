/**
 * 录制内核：MediaRecorder + IndexedDB 分片队列（源：`bank-media.js` + `agent_evidence.js`）。
 *
 * ★ 纯 JS、框架无关。不引第三方录制库——空分片上报与 0 字节如实提示的语义必须自己掌控。
 *
 * 红线（逐条来自基线，不得简化）：
 *  1. **空分片同样上报**。用 `size > 0` 过滤会导致"录满 40 秒、落盘 0 字节却报存证成功"——
 *     服务端据此才能把「录到了但没数据」与「根本没录」区分开。
 *  2. **先落 IndexedDB 再顺序上传**；页面重载后按同一 `recordingId` 补传。
 *  3. **首片登记空分片**：断网重连时也能保留"本段已开始"的可靠顺序标识。
 *  4. `fileSize === 0` 的回执**如实返回**，由业务侧决定是否提示；本模块绝不宣告成功。
 *  5. **上传失败重试沿用同一 eventId**：重试读的是同一个 `[recordingId, chunkIndex]` 行，
 *     不新建编号——否则服务端会把一次重试当成两片，落盘顺序错乱。
 *
 * 与 navis 基线的对应关系：
 *  - `ChunkQueue` 对齐 `navis-web/public/js/bank-media.js`（落库 → flush 顺序上传 → 删行 → 失败保留行）。
 *  - `SegmentRecorder` 的分片链路对齐 `agent_evidence.js` 的 `uploadChain`：
 *    持续上报 fire-and-forget 但**串成一条链**，封存前 `await uploadChain` 等全部排空。
 *  - 已知差异：navis 的 `flush()` 空队列时返回 null；本实现回退到 `lastReceipt`（最近一次成功回执），
 *    否则封存时最后一片早已上传完的场景会把回执丢成 `fileSize: 0`（详见 README「行为对照」）。
 */

/** 分片上传的接口字段，顺序即 FormData 的追加顺序，与后端 stream-chunk 对齐。 */
export const CHUNK_FIELDS = [
    'bizNo', 'processId', 'segmentIndex', 'handlerType', 'handlerName',
    'duration', 'nodesCovered', 'isLast', 'recordingId', 'chunkIndex'
];

const DB_NAME = 'bank-media-outbox';
const STORE = 'chunks';
const DB_VERSION = 1;

/**
 * @typedef {{key?:string, recordingId:string, chunkIndex:number, startedAt?:number,
 *            blob?:Blob, isLast?:boolean, [field:string]:unknown}} ChunkRow
 * @typedef {{success:boolean, fileSize:number, recordingId?:string|null,
 *            chunks?:number, duration?:number, message?:string, [field:string]:unknown}} SealReceipt
 */

/** 生成 recordingId：优先沿用宿主的链路 id，便于与留痕串起来。 */
export function makeRecordingId(prefix = 'agent') {
    const random = Math.random().toString(16).slice(2, 8);
    return `${prefix}-${Date.now()}-${random}`;
}

/**
 * IndexedDB 分片队列（源：`bank-media.js`）。
 *
 * 做成类而不是模块级单例，是因为单测要注入 fake indexedDB，
 * 且将来同一页面可能同时存在坐席段与本地段两个录制。
 */
export class ChunkQueue {
    constructor(options = {}) {
        this.dbName = options.dbName || DB_NAME;
        this.upload = options.upload || null;
        this.idb = options.indexedDB ?? globalThis.indexedDB ?? null;
        this.BlobCtor = options.Blob ?? globalThis.Blob;
        this.db = null;
        this.flushing = null;
        this.lastError = null;
        this.retries = 0;
        /** 最近一次成功上传的回执：flush 无可传时回退返回，封存据此不丢最后一片的回执。 */
        this.lastReceipt = null;
    }

    async open() {
        if (this.db) return this.db;
        if (!this.idb) throw new Error('当前环境不支持 IndexedDB，录像分片无法本地暂存');
        this.db = await new Promise((resolve, reject) => {
            const request = this.idb.open(this.dbName, DB_VERSION);
            request.onupgradeneeded = () => {
                const db = request.result;
                if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: 'key' });
            };
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(new Error('无法保存本地录像分片，请保留页面并检查存储权限'));
        });
        return this.db;
    }

    /**
     * 把一次 IDB 事务包成 Promise。
     *
     * ★ 关键：`work()` 返回的是 **IDBRequest**，真正的数据在 `request.result` 上。
     *   早期版本直接 resolve 了这个 request 对象，于是 `getAll()` 拿到的是一个非数组的
     *   请求句柄，`rows.length` 恒为 undefined → **队列一次都没上传**，
     *   「空分片上报」与「0 字节不得宣告成功」两条红线形同未执行。
     *   这里统一按 request 解包 `.result`。
     */
    async operation(mode, work) {
        const db = await this.open();
        return new Promise((resolve, reject) => {
            let request;
            const transaction = db.transaction(STORE, mode);
            try {
                request = work(transaction.objectStore(STORE));
            } catch (error) {
                reject(error instanceof Error ? error : new Error(String(error)));
                return;
            }
            transaction.oncomplete = () => {
                const value = request && typeof request === 'object' && 'result' in request
                    ? request.result
                    : request;
                resolve(value);
            };
            transaction.onerror = () => reject(new Error('录像待传记录未能保存'));
            transaction.onabort = () => reject(new Error('录像待传记录未能保存'));
        });
    }

    /**
     * 顺序上传所有待传分片，返回**最后一片的回执**。
     *
     * 排序按 `startedAt` 再按 `chunkIndex`：重载补传时队列里可能混着上一段与新段的记录，
     * 只按 chunkIndex 排会把上一段的大序号排到新段前面。
     *
     * @param rethrow 为 false 时吞掉上传异常（用于 ondataavailable 的持续上报），
     *                异常时行仍留在库里，下次 flush 沿用同一 eventId 重试。
     * @returns {Promise<object|null>} 本轮最后一片的回执；本轮无片可传时回退到
     *          `lastReceipt`（最近一次成功回执），一次都没成功过才是 null。
     */
    async flush(rethrow = true) {
        if (this.flushing) return this.flushing;
        this.flushing = (async () => {
            let receipt = null;
            for (let guard = 0; guard < 10000; guard += 1) {
                const rows = await this.operation('readonly', store => store.getAll());
                if (!rows || !rows.length) return receipt ?? this.lastReceipt;
                rows.sort((a, b) => (a.startedAt || 0) - (b.startedAt || 0) || (a.chunkIndex || 0) - (b.chunkIndex || 0));
                const item = rows[0];
                const form = new FormData();
                form.append('chunk', item.blob ?? new this.BlobCtor([]));
                CHUNK_FIELDS.forEach(name => form.append(name, String(item[name] ?? '')));
                try {
                    receipt = await this.upload(form, item);
                    this.lastReceipt = receipt;
                    this.lastError = null;
                } catch (error) {
                    // ★ 失败不清行：下次 flush 读到的仍是同一行（同一 recordingId + chunkIndex），
                    //   这就是「重试沿用同一 eventId」——服务端不会把重试当成两片。
                    this.retries += 1;
                    this.lastError = error instanceof Error ? error : new Error(String(error));
                    if (rethrow) throw this.lastError;
                    return receipt;
                }
                await this.operation('readwrite', store => store.delete(item.key));
            }
            return receipt;
        })();
        try {
            return await this.flushing;
        } finally {
            this.flushing = null;
        }
    }

    /**
     * 入队一片并尝试上传。
     * ★ 不校验 size：空分片也要入队上报。
     */
    async enqueue(item) {
        const row = {
            ...item,
            startedAt: item.startedAt ?? Date.now(),
            key: `${item.recordingId}:${item.chunkIndex}`
        };
        await this.operation('readwrite', store => store.put(row));
        // 持续上报路径不因网络异常中断录制：分片已落库，稍后补传。
        const receipt = await this.flush(false);
        // 正在收尾的上传可能读到了空队列，而新分片刚写完；再检查一次防止漏传最后一片。
        const remaining = await this.operation('readonly', store => store.getAll());
        return remaining && remaining.length ? this.flush(false) : receipt;
    }

    async pending() {
        const rows = await this.operation('readonly', store => store.getAll());
        return (rows || []).slice().sort((a, b) => (a.startedAt || 0) - (b.startedAt || 0) || (a.chunkIndex || 0) - (b.chunkIndex || 0));
    }

    async clear() {
        await this.operation('readwrite', store => store.clear());
        return true;
    }
}

/**
 * 分段录制器：管 MediaRecorder 生命周期 + 分片入队 + 封存。
 *
 * 依赖全部注入（`MediaRecorder` / `Blob` / `queue` / `compose`），
 * 因此 node 单测可以用假实现跑完整条链路。
 */
export class SegmentRecorder {
    constructor(options) {
        this.queue = options.queue;
        this.MediaRecorderCtor = options.MediaRecorder ?? globalThis.MediaRecorder ?? null;
        this.BlobCtor = options.Blob ?? globalThis.Blob;
        this.mimeCandidates = options.mimeCandidates || ['video/webm;codecs=vp8,opus', 'video/webm'];
        this.timeslice = options.timeslice ?? 1000;

        this.recorder = null;
        this.recordingId = null;
        this.meta = null;
        this.startedAt = 0;
        this.chunkIndex = 0;
        this.chunks = [];
        this.closing = false;
        this.sealing = null;
        this.mimeType = null;
        this.reportedChunks = 0;
        this.emptyChunks = 0;
        this.onError = null;
        /**
         * 分片上报链（对齐 navis `agent_evidence.js` 的 uploadChain）：
         * fire-and-forget 不阻塞录制，但全部串在一条链上，封存与单测通过它等排空。
         */
        this.uploadChain = Promise.resolve();
    }

    get active() {
        return Boolean(this.recorder && this.recorder.state === 'recording');
    }

    /** 选一个浏览器支持的封装格式；都不支持时抛错而不是让 MediaRecorder 自己在构造时炸。 */
    pickMimeType() {
        const supported = typeof this.MediaRecorderCtor?.isTypeSupported === 'function';
        const found = supported
            ? this.mimeCandidates.find(type => this.MediaRecorderCtor.isTypeSupported(type))
            : this.mimeCandidates[0];
        if (!found) throw new Error('浏览器不支持当前录像格式');
        return found;
    }

    /**
     * 把一片分片挂到上报链尾部（fire-and-forget：录制不被网络拖住，
     * 分片已落库，失败由后续 flush 沿用同一编号补传）。
     */
    _enqueue(item) {
        this.uploadChain = this.uploadChain
            .then(() => this.queue.enqueue(item))
            .catch(error => { this.emitError(error); });
        return this.uploadChain;
    }

    /** 等所有已入队分片上报完毕（封存前与单测断言前使用）。 */
    async whenIdle() {
        await this.uploadChain;
        return true;
    }

    async start(stream, meta = {}) {
        if (this.active) return this.recordingId;
        if (this.sealing) throw new Error('上一段录像尚未上传，请先完成上传重试');
        if (!stream) throw new Error('未提供可录制的媒体流');
        if (!this.MediaRecorderCtor) throw new Error('当前浏览器不支持录制，请使用支持 MediaRecorder 的浏览器');
        if (!this.queue) throw new Error('录像上传组件未加载，请刷新页面后重新接听');

        this.mimeType = this.pickMimeType();
        this.meta = {
            processId: meta.processId || '',
            bizNo: meta.bizNo || '',
            segmentIndex: Number(meta.segmentIndex) || 1,
            nodeName: meta.nodeName || '人工办理',
            handlerType: meta.handlerType || 'HUMAN_AGENT',
            handlerName: meta.handlerName || '远程坐席',
            nodesCovered: meta.nodesCovered ?? meta.nodeName ?? '人工办理'
        };
        this.recordingId = meta.recordingId || makeRecordingId(this.meta.handlerType === 'DIGITAL_HUMAN' ? 'cust' : 'agent');
        this.chunkIndex = 0;
        this.chunks = [];
        this.closing = false;
        this.reportedChunks = 0;
        this.emptyChunks = 0;
        this.startedAt = Date.now();

        const enqueue = (blob, isLast) => {
            const item = {
                ...this.meta,
                duration: Math.max(1, Math.round((Date.now() - this.startedAt) / 1000)),
                recordingId: this.recordingId,
                chunkIndex: this.chunkIndex++,
                blob,
                isLast: Boolean(isLast),
                startedAt: Date.now()
            };
            // ★ 空分片同样入队。这里如果加 `if (blob.size > 0)`，
            //   就会重现"录满 40 秒、落盘 0 字节却报存证成功"。
            if (!blob || blob.size === 0) this.emptyChunks += 1;
            this.reportedChunks += 1;
            this._enqueue(item);
        };

        this.recorder = new this.MediaRecorderCtor(stream, { mimeType: this.mimeType });
        this.recorder.ondataavailable = event => {
            if (!event.data) return;
            this.chunks.push(event.data);
            enqueue(event.data, this.closing);
        };
        this.recorder.onerror = () => {
            this.emitError(new Error('录制过程异常，请保留页面并重试'));
        };

        // 先登记一片空分片：断网重连时也能保留"本段已开始"的可靠顺序标识。
        // 这里也不因网络异常失败——首片已落库，重试沿用同一编号即可。
        this._enqueue({
            ...this.meta,
            duration: 1,
            recordingId: this.recordingId,
            chunkIndex: this.chunkIndex++,
            blob: new this.BlobCtor([], { type: this.mimeType }),
            isLast: false,
            startedAt: Date.now()
        });
        await this.uploadChain;
        this.reportedChunks += 1;
        this.emptyChunks += 1;

        this.recorder.start(this.timeslice);
        return this.recordingId;
    }

    emitError(message, cause) {
        const error = message instanceof Error ? message : new Error(String(message));
        if (cause !== undefined) error.cause = cause;
        this.onError?.(error);
        return error;
    }

    /**
     * 封存：停录制 → 等上报链与队列排空 → 返回**最后一片的回执**。
     *
     * 回执含 `fileSize`。**fileSize === 0 时如实返回**，不加工、不补默认值、不宣告成功
     * （`success` 在 0 字节时固定落 false，§3.4 红线）；是否提示由业务侧决定。
     * 上传失败时这里**抛出**（与持续上报不同），让业务侧知道这一段没封存成功。
     */
    async seal() {
        if (this.sealing) return this.sealing;
        if (!this.active && !this.recordingId) return { success: false, fileSize: 0, message: '当前没有进行中的录制' };

        this.sealing = (async () => {
            if (this.active) {
                const active = this.recorder;
                this.closing = true;
                await new Promise((resolve, reject) => {
                    active.onstop = () => resolve();
                    active.onerror = () => reject(new Error('录像封存失败，请保留页面并重试'));
                    try {
                        active.stop();
                    } catch (error) {
                        reject(new Error('录像封存失败，请保留页面并重试'));
                    }
                });
                this.recorder = null;
            }
            // 对齐 navis stopAndUpload：先等 uploadChain 排空，再 flush 队列剩余。
            await this.uploadChain;
            const receipt = await this.queue.flush(true);
            const result = {
                recordingId: this.recordingId,
                chunks: this.reportedChunks,
                duration: Math.max(1, Math.round((Date.now() - this.startedAt) / 1000)),
                ...(receipt || {})
            };
            result.fileSize = Number(result.fileSize) || 0;
            // ★ 红线：fileSize === 0 不得宣告存证成功（服务端 0 字节 = 没录到内容）。
            result.success = Boolean(result.success) && result.fileSize > 0;
            // 0 字节时补一句可直接使用的人话，业务侧可直接透传；不改动 success 语义。
            if (result.fileSize === 0) result.message = result.message || '该段未采集到画面';
            // 本段已封存：清掉 recordingId，再次 seal 走「没有进行中的录制」而不是返回旧回执。
            this.recordingId = null;
            return result;
        })();

        try {
            return await this.sealing;
        } finally {
            this.sealing = null;
        }
    }

    /** 放弃本段：停录制但**不封存**（例如业务驳回后作废）。 */
    async abort() {
        if (this.active) {
            try {
                this.recorder.stop();
            } catch (error) {
                /* 忽略 */
            }
            this.recorder = null;
        }
        this.recordingId = null;
        this.chunks = [];
        return true;
    }
}

export default SegmentRecorder;
