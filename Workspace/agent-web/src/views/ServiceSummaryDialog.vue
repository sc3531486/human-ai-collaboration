<template>
    <el-dialog v-model="visible" class="service-summary-dialog" width="80%" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" title="服务小结" @closed="reset">
        <template v-if="loading"><div class="service-summary-loading">正在加载本次服务信息…</div></template>
        <template v-else-if="draft">
            <section class="service-summary-status"><vb-icon name="check" :size="20" /><div><strong>{{ completed ? '本次业务已办结' : '本次视频服务已结束' }}</strong><span>{{ draft.businesses?.length || 0 }} 笔业务 · {{ draft.endedBy === 'CUSTOMER' ? '客户挂断' : '坐席挂断' }}</span></div></section>
            <section class="service-summary-profile" aria-label="客户与服务信息">
                <div><span>客户姓名</span><b>{{ draft.customerName || '—' }}</b></div>
                <div><span>客户号</span><b>{{ draft.customerId || '—' }}</b></div>
                <div><span>预留手机号</span><b>{{ draft.customerMobileMasked || '—' }}</b></div>
                <div><span>进线渠道</span><b>{{ draft.incomingChannel || '—' }}</b></div>
                <div><span>服务坐席</span><b>{{ identity.agentName }} · {{ identity.agentId }}</b></div>
                <div><span>服务时段</span><b>{{ servicePeriod }}</b></div>
            </section>
            <section class="service-summary-section">
                <h3>本次办理业务</h3><p>办理结果以业务系统记录为准。</p>
                <el-table :data="draft.businesses" size="small" class="service-summary-table">
                    <el-table-column prop="bizNo" label="业务流水" min-width="190" />
                    <el-table-column prop="tradeName" label="业务名称" min-width="150" />
                    <el-table-column label="办理结果" width="110"><template #default="scope"><span class="service-summary-result">{{ resultLabel(scope.row.handlingResult) }}</span></template></el-table-column>
                </el-table>
            </section>
            <section class="service-summary-section">
                <h3>本次服务情况</h3><p>请选择与本次办理相符的标签，可多选。</p>
                <div class="service-summary-tags"><el-check-tag v-for="tag in tags" :key="tag" :checked="selected.includes(tag)" @change="toggle(tag)">{{ tag }}</el-check-tag></div>
                <el-input v-model="remark" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="填写需要留意的服务情况（选填）" />
            </section>
        </template>
        <template #footer><el-button :disabled="saving" @click="visible=false">稍后填写</el-button><el-button type="primary" :loading="saving" :disabled="!draft || !draft.businesses?.length" @click="submit">提交小结</el-button></template>
    </el-dialog>
</template>

<script setup>
import { computed, ref, onBeforeUnmount } from 'vue';
import { ElMessage } from 'element-plus';
import VbIcon from '@/ui/VbIcon.vue';
import { request } from '@/core/sdk.js';
import { bus, report } from '@/core/bus.js';
import { identity } from '@/core/identity.js';

const visible = ref(false); const loading = ref(false); const saving = ref(false); const draft = ref(null); const selected = ref([]); const remark = ref('');
let generation = 0;
const completed = computed(() => draft.value?.businesses?.length > 0 && draft.value.businesses.every(item => item.handlingResult === 'COMPLETED'));
const tags = ['资料核验完成', '客户沟通顺畅', '需后续关注', '客户主动挂断'];
const servicePeriod = computed(() => [formatTime(draft.value?.serviceStartedAt), formatTime(draft.value?.serviceEndedAt)].filter(Boolean).join(' 至 ') || '—');

const off = bus.on('service-summary:open', event => { void open(event); });
onBeforeUnmount(() => { generation++; off(); });
async function open(event) {
    if (!event?.callId || !identity.agentId) return;
    const token = ++generation;
    loading.value = true; visible.value = true; draft.value = null; selected.value = []; remark.value = '';
    try {
        const data = await request(`/api/agent-service-summaries/draft?callId=${encodeURIComponent(event.callId)}&agentId=${encodeURIComponent(identity.agentId)}`);
        if (token !== generation) return;
        draft.value = data; selected.value = Array.isArray(data.labels) ? data.labels : []; remark.value = data.remark || '';
    } catch (error) { visible.value = false; report('服务小结加载', error); ElMessage.error(error.message || '服务小结加载失败'); }
    finally { if(token === generation) loading.value = false; }
}
function toggle(tag) { selected.value = selected.value.includes(tag) ? selected.value.filter(item => item !== tag) : [...selected.value, tag]; }
function resultLabel(value) { return ({ COMPLETED: '已完成', PROCESSING: '办理中', FAILED: '未完成' })[value] || '—'; }
function formatTime(value) { if (!value) return ''; const date = new Date(value); return Number.isNaN(date.getTime()) ? String(value).replace('T', ' ') : date.toLocaleString('zh-CN', { hour12: false }); }
async function submit() { if (!draft.value || saving.value) return; saving.value = true; try { await request('/api/agent-service-summaries', { callId: draft.value.callId, agentId: identity.agentId, labels: selected.value, remark: remark.value }); ElMessage.success('服务小结已提交'); visible.value = false; } catch (error) { report('服务小结提交', error); ElMessage.error(error.message || '服务小结提交失败'); } finally { saving.value = false; } }
function reset() { generation++; draft.value = null; selected.value = []; remark.value = ''; }
</script>
