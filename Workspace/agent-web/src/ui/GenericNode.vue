<template>
    <!--
      通用兜底节点节点页（契约 §2.1 第 4 步）★ "新增场景零改动"的关键。
      纯靠后端 nodePolicy 渲染并推进流程：displayFields 只读展示 + fields 表单 + actions 生成驳回按钮。
      policy.view === 'witness' 时额外提供现场照片采集（走 capture() 命令 API，不碰 video DOM）。
    -->
    <section class="vb-generic-node">
        <vb-node-frame :label="label" :title="title" :description="description">
            <div v-if="displayRows.length" class="business-element-grid">
                <label v-for="field in displayRows" :key="field.name">
                    {{ field.label }}
                    <a v-if="imageOf(field)" :href="imageOf(field)" target="_blank" rel="noopener">
                        <img :src="imageOf(field)" :alt="field.label">
                    </a>
                    <a v-else-if="documentOf(field)" :href="documentOf(field)" target="_blank" rel="noopener">下载PDF回执</a>
                    <input v-else readonly :value="String(field.value ?? '—')">
                </label>
            </div>

            <!-- 历史还原：展示当时留存的字段值，不可编辑 -->
            <div v-if="readOnly && !formFields.length" class="historical-form">
                <div v-for="field in policy.fields || []" :key="field.name">
                    <span>{{ field.label }}</span><strong>{{ field.value ?? '当时未留存' }}</strong>
                </div>
            </div>

            <p v-if="notice" class="node-notice">{{ notice }}</p>

            <vb-photo-capture v-if="showCapture" ref="captureRef" :disabled="readOnly" @captured="onCaptured" />

            <vb-policy-form v-if="showForm" ref="formRef" :state="state" :fields="formFields"
                :action-label="policy.actionLabel || '提交'" :disabled="!editable" @submit="onSubmit">
                <template v-if="rejectable" #actions>
                    <button type="button" class="secondary-button" @click="onReject">退回重新签名</button>
                </template>
            </vb-policy-form>

            <!-- 办结节点：确认办结前必须先把证据封存送去佐证 -->
            <div v-if="showComplete" class="node-actions">
                <button class="primary-button" type="button" :disabled="readOnly || !owned" @click="complete">
                    {{ readOnly ? '历史结果 · 只读' : (owned ? '已告知客户，完成办理' : '等待客户确认结果') }}
                </button>
            </div>

        </vb-node-frame>
    </section>
</template>

<script setup>
import { computed, ref } from 'vue';
import VbNodeFrame from './VbNodeFrame.vue';
import VbPolicyForm from './VbPolicyForm.vue';
import VbPhotoCapture from './VbPhotoCapture.vue';
import { submitNode } from '@/core/flow/actions.js';
import { video } from '@/core/video-widget.js';
import { evidence as evidenceApi } from '@/core/sdk.js';
import { safeRecordUrl, safeDocumentUrl } from '@/core/format.js';
import { identity } from '@/core/identity.js';

const props = defineProps({
    state: { type: Object, required: true },
    readOnly: { type: Boolean, default: false }
});

const formRef = ref(null);
const captureRef = ref(null);
let photoBlob = null;

const policy = computed(() => props.state.nodePolicy || {});
const vars = computed(() => props.state.vars || {});
const label = computed(() => policy.value.kicker || '');
const title = computed(() => policy.value.title || props.state.nodeName || '业务结果');
const description = computed(() => policy.value.description || '请核对信息后提交。');

/** 是否归属当前坐席：只有绑定的办理坐席才能提交。 */
const owned = computed(() => {
    const owner = vars.value.agent_session_id || vars.value.last_agent_id;
    return !owner || owner === identity.agentId;
});
const actors = computed(() => policy.value.actors || []);
const editable = computed(() => !props.readOnly && actors.value.includes('AGENT') && owned.value);

/** displayFields 优先，缺省回落到 fields（两者语义不同时后端会同时下发）。 */
const displayRows = computed(() => (policy.value.displayFields || policy.value.fields || [])
    .filter(field => field.type !== 'hidden')
    .map(field => ({ ...field, value: field.type === 'image' || field.type === 'document' ? vars.value[field.name] : vars.value[field.name] })));
const imageOf = field => (field.type === 'image' ? safeRecordUrl(field.value) : '');
const documentOf = field => (field.type === 'document' ? safeDocumentUrl(field.value) : '');

const formFields = computed(() => (policy.value.fields || []));
const showForm = computed(() => formFields.value.length > 0 && (editable.value || props.readOnly));
const rejectable = computed(() => !props.readOnly && (policy.value.actions || []).includes('REJECT') && editable.value);
const showCapture = computed(() => policy.value.view === 'witness' && editable.value);
const showComplete = computed(() => (policy.value.actions || []).includes('COMPLETE') || policy.value.view === 'result');

const notice = computed(() => {
    if (props.readOnly) return '';
    if (!actors.value.includes('AGENT')) return '客户正在核对业务信息，当前页面只读。';
    if (vars.value.agent_session_id && vars.value.agent_session_id !== identity.agentId) return '该业务已由其他坐席接管，当前页面只读。';
    if (!vars.value.agent_session_id && policy.value.video) return '当前业务尚未绑定办理坐席，请认领后继续核验和提交。';
    return '';
});

function onCaptured(blob) { photoBlob = blob; }

/**
 * 默认提交。见证类节点要求先有现场照片，再取证据归档回执写回表单，
 * 这一步失败就不放行——否则"声称已核验"与"确有影像"会脱节。
 */
async function onSubmit(values) {
    if (showCapture.value) {
        if (!photoBlob) throw new Error('请先拍照并核对照片');
        const saved = await evidenceApi.photo(props.state, 'witness_photo', photoBlob, { agentId: identity.agentId });
        values.evidence_receipt = saved.eventId || '';
    }
    await submitNode(props.state, values);
    formRef.value?.clearDraft();
}

/** 驳回 / 退回重签：旧签名与审核意见由后端保留，前端只负责把退回原因送达。 */
async function onReject() {
    const values = formRef.value?.read() || {};
    const reason = String(values.review_reason ?? values.reject_reason ?? '').trim();
    if (!reason) throw new Error('请填写退回原因');
    await submitNode(props.state, { ...values, review_reason: reason }, { action: 'REJECT' });
}

/**
 * 办结：先把这一段的人工见证录像封存上去佐证，再推进流程。
 * ★ seal() 回执 fileSize === 0 时**不得**宣告成功（§3.4），如实提示未采集到画面。
 */
async function complete() {
    const receipt = await video.record.seal();
    if (!receipt || receipt.fileSize === 0) throw new Error('该段未采集到画面，录像未封存成功');
    await submitNode(props.state, {});
}
</script>
