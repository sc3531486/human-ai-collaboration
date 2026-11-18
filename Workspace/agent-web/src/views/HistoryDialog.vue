<template>
    <!--
      业务与影像详情：三种入口（summary/image/video）共用同一份留痕数据，只是打开后的落点不同。
      ★ 影像 URL 必须命中 /records/<安全字符> 才渲染，防止任意 URL 注入（§3.7 红线）。
    -->
    <el-dialog v-model="visible" width="76%" @closed="onClosed">
        <template #header>
            <div class="dialog-heading">
                <div><h2>{{ titles[mode] }}</h2></div>
            </div>
        </template>

        <div v-if="!detail" class="empty-state"><p>正在调阅业务记录…</p></div>
        <template v-else>
            <el-tabs v-model="mode"><el-tab-pane label="业务详情" name="summary" /><el-tab-pane label="影像调阅" name="image" /><el-tab-pane label="视频调阅" name="video" /></el-tabs>
            <div v-if="mode === 'summary'" class="summary-grid">
                <div><span>客户姓名</span><strong>{{ detail.customer_name }}</strong></div>
                <div><span>交易金额</span><strong>{{ money(detail.amount) }}</strong></div>
                <div><span>业务流水号</span><strong class="small">{{ detail.biz_no }}</strong></div>
                <div><span>办理状态</span><strong>{{ statuses[detail.status] || '待确认' }}</strong></div>
            </div>

            <template v-if="mode === 'image'">
                <div class="history-image-grid"><figure v-for="image in images" :key="image.url"><img v-if="!image.document" :src="image.url" :alt="image.label" loading="lazy"><a v-else :href="image.url" target="_blank" rel="noopener">打开业务办理单 / 回执</a><figcaption>{{ image.label }}</figcaption></figure></div>
                <div v-if="!images.length" class="empty-state"><p>暂无已归档的业务图片或单据</p></div>
            </template>
            <template v-if="mode === 'video'">
            <section v-for="segment in detail.segments || []" :key="segment.segment_index" class="record-segment">
                <h3>第 {{ segment.segment_index }} 段 · {{ segment.handler_name }}</h3>
                <small>{{ segment.nodes_covered || '业务录制' }} · {{ segment.duration_seconds || 0 }} 秒</small>
                <video v-if="safe(segment.file_url)" :src="safe(segment.file_url)" controls preload="metadata"></video>
                <p v-else>影像地址不可用</p>
                <small>SHA-256：{{ segment.file_hash || '—' }}</small>
            </section>
            <div v-if="!(detail.segments || []).length" class="empty-state">
                <p>暂无已上传的录像分段</p>
            </div>
            </template>
        </template>
    </el-dialog>
</template>

<script setup>
import { ref, computed, onBeforeUnmount } from 'vue';
import { bus, report } from '@/core/bus.js';
import { request } from '@/core/sdk.js';
import { money, safeRecordUrl } from '@/core/format.js';

const visible = ref(false);
const detail = ref(null);
const mode = ref('summary');
const traces = ref([]);
const titles = { summary: '业务详情', image: '影像调阅', video: '视频调阅' };
const statuses = { COMPLETED: '已完成', PROCESSING: '办理中', FAILED: '办理失败', REJECTED: '已终止' };
let generation = 0;
const images = computed(() => {
    const seen = new Set(); const rows = [];
    for (const trace of traces.value) {
        const payload = trace.payload || {};
        for (const raw of [payload.fileUrl, payload.receiptPdfUrl, payload.documentUrl]) {
            const url = safeRecordUrl(raw);
            if (!url || seen.has(url) || !/\.(png|jpe?g|webp|pdf)$/i.test(url)) continue;
            seen.add(url); rows.push({ url, document: /\.pdf$/i.test(url), label: payload.label || payload.kind || trace.nodeName || '业务影像' });
        }
    }
    return rows;
});

const off = bus.on('history:open', async target => {
    const bizNo = typeof target === 'string' ? target : target.bizNo;
    mode.value = typeof target === 'object' && titles[target.mode] ? target.mode : 'summary';
    const token = ++generation;
    visible.value = true;
    detail.value = null;
    try {
        const [response, evidence] = await Promise.all([request(`/api/balance/history/detail?bizNo=${encodeURIComponent(bizNo)}`),request(`/api/evidence/trace?bizNo=${encodeURIComponent(bizNo)}&afterId=0&limit=200`)]);
        if (token !== generation) return;
        if (!response.data) throw new Error('业务记录详情未返回 data 字段');
        detail.value = response.data;
        traces.value = evidence.rows || [];
    } catch (error) {
        // 调阅失败：关窗不展示过期内容，同时带码呈现根因（console + system:error 一并落账）。
        report('业务记录详情调阅', error);
        visible.value = false;
    }
});
onBeforeUnmount(() => { generation++;off(); });

const safe = url => safeRecordUrl(url);

// 关闭对话框时释放 <video>：不释放的话后台仍在拉流，影像列表翻页时会出现多路解码。
function onClosed() {
    generation++;
    detail.value = null;
    traces.value = [];
}
</script>
