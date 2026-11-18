<template>
    <!-- 只读要素表：替代 node_ui.js 的 NodeUI.facts 内联串接，class 沿用 agent_nodes.css。 -->
    <dl class="node-facts">
        <div v-for="row in rows" :key="row.label">
            <dt>{{ row.label }}</dt>
            <dd>{{ row.value ?? '—' }}</dd>
        </div>
    </dl>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
    rows: { type: Array, default: () => [] }   // [[label, value]] 或 [{label, value}]
});

const rows = computed(() => props.rows.map(row => Array.isArray(row) ? { label: row[0], value: row[1] } : row));
</script>
