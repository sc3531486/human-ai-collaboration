<template>
    <!--
      密码重置 / 人工核实手持证件并授权：素材 nodes/password-authorize.js。
      人脸分数接近阈值时给出更有信息量的提示；现场照片走 capture() 命令 API。
    -->
    <vb-node-frame label="密码重置" :title="policy.title || '手持证件现场核实'" :description="reason">
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
import VbPhotoCapture from '@/ui/VbPhotoCapture.vue';
import VbPolicyForm from '@/ui/VbPolicyForm.vue';
import { submitNode } from '@/core/flow/actions.js';
import { evidence as evidenceApi } from '@/core/sdk.js';
import { identity } from '@/core/identity.js';

const props = defineProps({ state: { type: Object, required: true }, readOnly: { type: Boolean, default: false } });
const policy = computed(() => props.state.nodePolicy || {});
const vars = computed(() => props.state.vars || {});

// 有比对分数时明确给出"多少分 / 阈值多少"，让坐席知道这次为什么还需要人工。
const reason = computed(() => (vars.value.faceScore !== undefined
    ? `本次人脸比对${vars.value.faceScore}分，通过阈值${vars.value.faceThreshold}分，请再次核实客户身份。`
    : '请核实客户身份和密码重置意愿。'));

let photo = null;
function onCaptured(blob) { photo = blob; }

async function onSubmit(values) {
    if (!photo) throw new Error('请先采集现场照片');
    const saved = await evidenceApi.photo(props.state, 'witness_photo', photo, { agentId: identity.agentId });
    values.evidence_receipt = saved.eventId || '';
    await submitNode(props.state, values);
}
</script>
