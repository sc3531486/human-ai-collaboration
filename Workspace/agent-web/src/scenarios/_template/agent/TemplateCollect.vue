<template>
    <!--
      模板节点组件示范：**不改任何已有文件**就能给新场景定制体验。
      素材来源 views/collect.js —— 标准表单复用内核渲染器，字段与校验全部来自后端 nodePolicy。
    -->
    <vb-node-frame :label="`场景 / ${state.nodeName || '节点'}`" :title="policy.title || state.nodeName || '信息采集'"
        :description="policy.description || '请核对业务要素后提交。'">
        <vb-policy-form v-if="!readOnly" :state="state" :fields="policy.fields || []"
            :action-label="policy.actionLabel || '提交'" @submit="onSubmit" />
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
import VbPolicyForm from '@/ui/VbPolicyForm.vue';
import { submitNode } from '@/core/flow/actions.js';

/**
 * 节点组件契约（§2.2）：
 *  - state：流程快照，**只读，勿改**；
 *  - readOnly：历史还原模式，必须禁用一切提交。
 */
const props = defineProps({
    state: { type: Object, required: true },
    readOnly: { type: Boolean, default: false }
});

const policy = computed(() => props.state.nodePolicy || {});

async function onSubmit(values) {
    // 提交一律走内核，组件内禁止直接 fetch。
    await submitNode(props.state, values);
}
</script>
