<template>
    <!-- 转账汇款 / 信息采集：素材 nodes/transfer-collect.js。要素只读核对 + 后代表单。 -->
    <vb-node-frame label="转账汇款 / 交易要素" :title="policy.title || '确认收款人与转账金额'"
        :description="policy.description || '核对数字人收集的交易要素，确认后提交风险评估。'">
        <vb-facts :rows="rows" />
        <vb-policy-form v-if="!readOnly" :state="state" :fields="policy.fields || []"
            :action-label="policy.actionLabel || '确认'" @submit="onSubmit" />
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
import VbPolicyForm from '@/ui/VbPolicyForm.vue';
import { submitNode } from '@/core/flow/actions.js';
import { money } from '@/core/format.js';

const props = defineProps({ state: { type: Object, required: true }, readOnly: { type: Boolean, default: false } });
const policy = computed(() => props.state.nodePolicy || {});
const vars = computed(() => props.state.vars || {});
const rows = computed(() => [
    ['收款人', vars.value.payee], ['转账金额', money(vars.value.amount)], ['用途', vars.value.purpose]
]);

async function onSubmit(values) { await submitNode(props.state, values); }
</script>
