<template>
    <section class="vb-node inline-node-history">
        <header class="inline-history-heading">
            <div>
                <span>业务记录</span>
                <h2>{{ name }}</h2>
            </div>
            <button type="button" class="secondary-button" @click="emit('close')">返回办理</button>
        </header>

        <label v-if="rows.length" class="restore-visit-selector-wrap">
            <span class="sr-only">选择业务记录</span>
            <select v-model="selected" class="restore-visit-selector" aria-label="选择业务记录" @change="draw">
                <option v-for="(row, index) in rows" :key="index" :value="index">
                    {{ row.nodeName }} · 第{{ row.visitSeq || 1 }}次 · {{ time(row.endedAt) }}
                </option>
            </select>
        </label>

        <p v-if="!rows.length" class="node-notice">暂无业务记录。</p>
        <div v-else-if="unavailable" class="node-notice">暂无可展示的业务资料。</div>
        <div v-else-if="component" class="inline-history-body">
            <component :is="component" v-bind="props" />
        </div>
        <p v-else class="node-notice">{{ error || '暂无可展示的业务资料。' }}</p>
    </section>
</template>

<script setup>
import { ref, computed, watch, onMounted, shallowRef } from 'vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { flow as flowApi, evidence as evidenceApi } from '@/core/sdk.js';
import * as router from '@/core/flow/router.js';
import { safeRecordUrl } from '@/core/format.js';
import { report } from '@/core/bus.js';

const props_ = defineProps({ nodeId: { type: String, required: true } });
const emit = defineEmits(['close']);

const store = useWorkspaceStore();
const rows = ref([]);
const selected = ref(0);
const component = shallowRef(null);
const props = ref({});
const unavailable = ref(false);
const error = ref('');
let events = [];

const name = computed(() => rows.value[Number(selected.value)]?.nodeName || '办理历史');
const time = value => (value ? new Date(value).toLocaleString('zh-CN') : '—');

async function load() {
    const state = store.business;
    rows.value = [];
    component.value = null;
    unavailable.value = false;
    error.value = '';
    if (!state?.processId) return;
    try {
        const [restore, list] = await Promise.all([flowApi.trail(state.processId), evidenceApi.list(state.bizNo)]);
        // trail 是轨迹接口的确定字段（后端恒定下发）：缺失即契约漂移，不拿空数组冒充「无历史」。
        if (!Array.isArray(restore.trail)) throw new Error('时光轨迹接口未返回 trail 列表');
        // 只保留已完成且命中该节点的访问记录；其余节点不参与还原。
        rows.value = restore.trail.filter(row => row.completed && row.nodeId === props_.nodeId);
        events = list || [];
        if (!rows.value.length) return;
        selected.value = String(rows.value.length - 1);
        draw();
    } catch (e) {
        // 面板内可见（含错误码）+ report 落 console/system:error。
        e.message = `历史访问记录加载失败：${e.message}`;
        report('历史访问记录加载', e);
        error.value = `${e.code || (e.status ? `HTTP_${e.status}` : 'APP_ERROR')} ${e.message}`;
    }
}

/**
 * 组装历史快照。
 * ★ readOnly 是硬性标记：所有参与渲染的组件都必须按它禁用提交，
 * 历史还原不允许改写任何业务状态。
 */
function draw() {
    const row = rows.value[Number(selected.value)];
    if (!row) return;
    const state = store.business;
    if (row.source === 'UNAVAILABLE') { unavailable.value = true; component.value = null; return; }
    unavailable.value = false;
    // 影像只有在 URL 合规、且确属本流程该节点的 CAPTURE_SAVED 事件时才参与还原。
    const evidence = events
        .filter(item => item.eventType === 'CAPTURE_SAVED' && item.processId === state.processId
            && item.nodeName === row.nodeName
            && (row.payload?.evidence_receipt
                ? item.eventId === row.payload.evidence_receipt
                : item.createdAt >= row.startedAt - 1000 && item.createdAt <= row.endedAt + 1000)
            && safeRecordUrl(item.payload?.fileUrl || ''))
        .map(item => item.payload);
    const snapshot = {
        ...state,
        processDefinitionId: state.processDefinitionId,
        processDefinitionKey: state.processDefinitionKey,
        nodeId: row.nodeId,
        nodeName: row.nodeName,
        // 轨迹行只携带 payload（与后端 TrailService 同字段口径），不猜别名、不补缺省。
        vars: { ...(row.payload || {}) },
        payload: row.payload || {},
        nodePolicy: row.nodePolicy || {},   // 轨迹接口不下发 nodePolicy：结构缺省，非失败掩盖
        evidence,
        readOnly: true
    };
    component.value = null;
    router.renderHistorical(snapshot)
        .then(result => {
            component.value = result.component;
            props.value = result.props;
        })
        .catch(e => {
            // 契约明示的「节点未配置界面」是可读空状态（专用错误码 NODE_UNCONFIGURED）；
            // 其余失败（组件动态导入、路由内部错误）必须带码呈现并上报，不得统统说成"暂未提供"。
            if (e?.code === 'NODE_UNCONFIGURED') { error.value = '暂无可展示的业务资料。'; return; }
            e.message = `历史节点界面还原失败：${e.message}`;
            report('历史节点界面还原', e);
            error.value = `${e.code || (e.status ? `HTTP_${e.status}` : 'APP_ERROR')} ${e.message}`;
        });
}

onMounted(load);
watch(() => props_.nodeId, load);
</script>
