<template>
    <!-- 流程步骤条：替代 updateState 里 process-steps 的内联串接。 -->
    <div class="vb-process-steps">
        <div v-for="(step, index) in steps" :key="step.nodeId || index" class="process-step" :class="stepClass(step)">
            <button v-if="done.has(step.nodeId)" type="button" @click="$emit('restore', step.nodeId)">
                <span class="step-number"><vb-icon name="check" :size="13" /></span>
                <span>{{ step.nodeName }}</span>
            </button>
            <template v-else>
                <span class="step-number">{{ String(index + 1).padStart(2, '0') }}</span>
                <span>{{ step.nodeName }}</span>
            </template>
        </div>
    </div>
</template>

<script setup>
import { computed } from 'vue';
import VbIcon from './VbIcon.vue';

const props = defineProps({
    steps: { type: Array, default: () => [] },        // [{nodeId, nodeName}]
    completed: { type: Array, default: () => [] },    // 已完成 nodeId
    activeNodeId: { type: String, default: '' },
    alert: { type: Boolean, default: false }          // 人工介入节点以红色告警呈现
});
defineEmits(['restore']);

const done = computed(() => new Set(props.completed));
const stepClass = step => {
    if (done.value.has(step.nodeId)) return 'done';
    if (step.nodeId !== props.activeNodeId) return '';
    return props.alert ? 'active alert' : 'active';
};
</script>
