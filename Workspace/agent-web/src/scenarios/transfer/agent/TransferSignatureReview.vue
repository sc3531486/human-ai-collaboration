<template>
    <!--
      转账汇款 / 人工核实签名：素材 nodes/transfer-signature.js 的 transferSignatureReview。
      通过走默认动作（AUTHORIZE，actionLabel 由 BPMN 声明）；签名不符走 REJECT，旧签名与审核意见由后端保留。
    -->
    <vb-node-frame label="转账汇款 / 人工核实签名" :title="policy.title || '核对客户签名与交易要素'"
        :description="policy.description || '签名核实通过并归档签字单后才允许正式转账；签名不符时退回客户重签。'">
        <vb-facts :rows="rows" />
        <!-- 客户签名图：URL 必须命中 /records/<安全字符> 才渲染（§3.7） -->
        <vb-evidence title="客户手写签字" :url="signatureUrl" />

        <vb-policy-form v-if="!readOnly" ref="formRef" :state="state" :fields="policy.fields || []"
            :action-label="policy.actionLabel || '核实通过并转账'" @submit="onApprove">
            <template #actions>
                <button type="button" class="secondary-button" @click="onReject">签名不符，退回重签</button>
            </template>
        </vb-policy-form>
        <div v-else class="historical-form">
            <div v-for="field in (policy.fields || [])" :key="field.name">
                <span>{{ field.label }}</span><strong>{{ state.vars?.[field.name] ?? '当时未留存' }}</strong>
            </div>
        </div>
    </vb-node-frame>
</template>

<script setup>
import { computed, ref } from 'vue';
import VbNodeFrame from '@/ui/VbNodeFrame.vue';
import VbFacts from '@/ui/VbFacts.vue';
import VbEvidence from '@/ui/VbEvidence.vue';
import VbPolicyForm from '@/ui/VbPolicyForm.vue';
import { submitNode } from '@/core/flow/actions.js';
import { money } from '@/core/format.js';

const props = defineProps({ state: { type: Object, required: true }, readOnly: { type: Boolean, default: false } });

const policy = computed(() => props.state.nodePolicy || {});
const vars = computed(() => props.state.vars || {});
const rows = computed(() => [
    ['收款人', vars.value.payee], ['转账金额', money(vars.value.amount)],
    ['收款账号', vars.value.payee_account], ['用途', vars.value.purpose]
]);
const signatureUrl = computed(() => vars.value.signature_image || '');
const formRef = ref(null);

async function onApprove(values) { await submitNode(props.state, values); }

/** 退回重签：必须填退回原因——缺省直接抛错提示，不代填、不默认（失败如实上抛）。 */
async function onReject() {
    const values = formRef.value?.read() || {};
    const reason = String(values.review_reason ?? '').trim();
    if (!reason) throw new Error('请填写退回原因');
    await submitNode(props.state, { review_reason: reason, confirmed: false }, { action: 'REJECT' });
}
</script>
