<template>
    <!-- 账户查询 / 人工核验：素材 nodes/balance-verify.js。现场照片走 capture() 命令 API。 -->
    <vb-node-frame label="账户查询 / 人工核验" :title="policy.title || '视频核实客户身份'"
        :description="policy.description || '与客户核对姓名、证件及查询意愿，保留现场照片与本次核验录像。'">
        <vb-facts :rows="rows" />
        <vb-photo-capture v-if="!readOnly" @captured="onCaptured" />
        <vb-policy-form v-if="!readOnly" :state="state" :fields="policy.fields || []"
            :action-label="policy.actionLabel || '确认核实'" @submit="onSubmit" />
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
import { submitNode } from '@/core/flow/actions.js';
import { evidence as evidenceApi } from '@/core/sdk.js';
import { identity } from '@/core/identity.js';

const props = defineProps({ state: { type: Object, required: true }, readOnly: { type: Boolean, default: false } });
const policy = computed(() => props.state.nodePolicy || {});
const vars = computed(() => props.state.vars || {});
const rows = computed(() => [
    ['客户姓名', vars.value.customer_name], ['证件信息', vars.value.customer_cert_masked], ['查询账户', vars.value.account_no]
]);

let photo = null;
function onCaptured(blob) { photo = blob; }

async function onSubmit(values) {
    if (!photo) throw new Error('请先采集现场照片');
    const saved = await evidenceApi.photo(props.state, 'witness_photo', photo, { agentId: identity.agentId });
    values.evidence_receipt = saved.eventId || '';
    await submitNode(props.state, values);
}
</script>
