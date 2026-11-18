<template>
    <!-- 转账汇款 / 办理结果：素材 nodes/transfer-result.js。坐席已退出，仅核对回执与通知状态。 -->
    <vb-node-frame label="转账汇款 / 办理结果" :title="policy.title || '转账已完成'"
        :description="policy.description || '正式转账、回执与通知由后台自动完成，坐席如需结果请通过历史查询调阅。'">
        <vb-facts :rows="rows" />
        <!-- PDF 回执单独校验扩展名，不符合则不渲染链接 -->
        <a v-if="receiptUrl" class="secondary-button document-link" :href="receiptUrl" target="_blank" rel="noopener" download>下载转账PDF回执</a>
    </vb-node-frame>
</template>

<script setup>
import { computed } from 'vue';
import VbNodeFrame from '@/ui/VbNodeFrame.vue';
import VbFacts from '@/ui/VbFacts.vue';
import { safeDocumentUrl } from '@/core/format.js';

const props = defineProps({ state: { type: Object, required: true }, readOnly: { type: Boolean, default: false } });

const policy = computed(() => props.state.nodePolicy || {});
const vars = computed(() => props.state.vars || {});
const rows = computed(() => [
    ['核心流水', vars.value.coreTxId], ['转账状态', vars.value.statusDesc],
    ['通知状态', vars.value.notificationStatus], ['收款人', vars.value.payee]
]);
const receiptUrl = computed(() => safeDocumentUrl(vars.value.receiptPdfUrl));
void props.readOnly;
void policy;
</script>
