<template>
    <!-- 密码重置 / 介质照片与卡号核对：素材 verification-elements.js 的 media()。 -->
    <vb-node-frame label="密码重置" :title="policy.title || '介质照片与卡号核对'"
        :description="policy.description || '请核对介质图片、识别卡号及与所选银行卡的比对结果。'">
        <div class="verification-photo-grid">
            <vb-evidence title="介质照片" :url="vars.media_photo" />
            <vb-evidence title="OCR识别图" :url="vars.ocrPhotoUrl" note="模拟OCR接口返回的图片" />
        </div>
        <vb-facts :rows="rows" />
    </vb-node-frame>
</template>

<script setup>
import { computed } from 'vue';
import VbNodeFrame from '@/ui/VbNodeFrame.vue';
import VbFacts from '@/ui/VbFacts.vue';
import VbEvidence from '@/ui/VbEvidence.vue';

const props = defineProps({ state: { type: Object, required: true }, readOnly: { type: Boolean, default: false } });
void props.readOnly;
const policy = computed(() => props.state.nodePolicy || {});
const vars = computed(() => props.state.vars || {});

const rows = computed(() => {
    const data = vars.value;
    const card = (data.cards || []).find(item => item.id === data.selected_card_id);
    return [
        ['客户选择的银行卡', data.selectedCardMasked || card?.cardNoMasked],
        ['OCR识别卡号', data.ocrCardNo],
        ['卡号比对结果', data.mediaMatched === undefined ? '等待识别' : data.mediaMatched ? '一致' : '不一致，请重新拍摄']
    ];
});
void policy;
</script>
