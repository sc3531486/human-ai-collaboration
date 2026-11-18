<template>
    <!--
      转账汇款 / 人工授权：素材 nodes/transfer-signature.js 的 transferAuthorize。
      ★ 现场照片一律走 §2.9 命令 API 的 capture('remote')，不触碰任何 <video> DOM。
    -->
    <vb-node-frame label="转账汇款 / 人工授权" :title="policy.title || '核实身份与交易信息'"
        :description="policy.description || '请核对本节点的收款人、账号和金额，拍摄现场照片后确认。'">
        <p v-if="risk" class="risk-note"><vb-icon name="info" :size="18" /><div><strong>{{ riskLevel }}</strong><br>{{ risk }}</div></p>
        <vb-facts :rows="rows" />
        <vb-photo-capture v-if="!readOnly" @captured="onCaptured" />
        <vb-policy-form v-if="!readOnly" :state="state" :fields="policy.fields || []"
            :action-label="policy.actionLabel || '确认授权'" @submit="onSubmit" />
        <div v-else class="historical-form">
            <div v-for="field in (policy.fields || [])" :key="field.name">
                <span>{{ field.label }}</span><strong>{{ state.vars?.[field.name] ?? '当时未留存' }}</strong>
            </div>
        </div>
    </vb-node-frame>
</template>

<script setup>
import { computed } from 'vue';
import VbNodeFrame from '@/ui/VbNodeFrame.vue';
import VbFacts from '@/ui/VbFacts.vue';
import VbPhotoCapture from '@/ui/VbPhotoCapture.vue';
import VbPolicyForm from '@/ui/VbPolicyForm.vue';
import VbIcon from '@/ui/VbIcon.vue';
import { submitNode } from '@/core/flow/actions.js';
import { money } from '@/core/format.js';
import { evidence as evidenceApi } from '@/core/sdk.js';
import { identity } from '@/core/identity.js';

const props = defineProps({ state: { type: Object, required: true }, readOnly: { type: Boolean, default: false } });

const policy = computed(() => props.state.nodePolicy || {});
const vars = computed(() => props.state.vars || {});
const rows = computed(() => [
    ['收款人', vars.value.payee], ['转账金额', money(vars.value.amount)], ['收款账号', vars.value.payee_account]
]);
const risk = computed(() => vars.value.risk_decision);
const riskLevel = computed(() => vars.value.risk_level || '需要人工复核');

let photo = null;
function onCaptured(blob) { photo = blob; }

/**
 * 授权提交：必须先有现场照片并归档成功，回执写回表单的隐藏字段 evidence_receipt。
 * 这一步不能跳过——否则"声称已现场核实"与"确有影像留痕"会脱节。
 */
async function onSubmit(values) {
    if (!photo) throw new Error('请先采集现场照片');
    const saved = await evidenceApi.photo(props.state, 'witness_photo', photo, { agentId: identity.agentId });
    values.evidence_receipt = saved.eventId || '';
    await submitNode(props.state, values);
}
</script>
