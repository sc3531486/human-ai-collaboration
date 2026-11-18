<template>
    <!-- 密码重置 / 身份证与现场人脸核验：素材 verification-elements.js 的 identity()。 -->
    <vb-node-frame label="密码重置" :title="policy.title || '身份证与现场人脸核验'"
        :description="policy.description || '请核对本次证件识别、联网核查及人脸比对结果。'">
        <div class="verification-photo-grid identity-photo-grid">
            <vb-evidence title="身份证人像面" :url="vars.id_front_photo" />
            <vb-evidence title="身份证国徽面" :url="vars.id_back_photo" />
            <vb-evidence title="现场人脸照片" :url="vars.face_photo" />
        </div>

        <section class="verification-result">
            <h3>身份证人像面识别结果</h3>
            <vb-facts :rows="frontRows" />
        </section>
        <section class="verification-result">
            <h3>身份证国徽面识别结果</h3>
            <vb-facts :rows="backRows" />
        </section>
        <section class="verification-result">
            <h3>身份核验结果</h3>
            <vb-facts :rows="checkRows" />
        </section>
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
const front = computed(() => vars.value.idFrontOcr || {});
const back = computed(() => vars.value.idBackOcr || {});

const frontRows = computed(() => [
    ['姓名', front.value.name], ['性别', front.value.gender], ['民族', front.value.nationality], ['证件号码', front.value.certNoMasked]
]);
const backRows = computed(() => [
    ['签发机构', back.value.issuer],
    ['有效期', back.value.validFrom && back.value.validTo ? `${back.value.validFrom} 至 ${back.value.validTo}` : '等待识别']
]);
// 联网核查：优先取接口返回的结构化结论，未返回时按布尔值回落，都不存在则如实标注等待核查。
const checkRows = computed(() => {
    const data = vars.value;
    return [
        ['联网核查', data.onlineResult?.message || (data.onlinePassed === undefined ? '等待核查' : data.onlinePassed ? '通过' : '未通过')],
        ['人脸比对分数', data.faceScore],
        ['本次通过阈值', data.faceThreshold]
    ];
});
void policy;
</script>
