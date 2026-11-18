<template>
    <!-- 本地 SVG 图标：path 表来自 core/icons.js，不依赖任何第三方图标库。 -->
    <span class="vb-icon" :style="{ color }" v-html="svg" aria-hidden="true"></span>
</template>

<script setup>
import { computed } from 'vue';
import { iconPaths } from '@/core/icons.js';

const props = defineProps({
    name: { type: String, default: 'file' },
    size: { type: [Number, String], default: 20 },
    color: { type: String, default: '' }
});

const size = computed(() => Number(props.size) || 20);
// 未登记的图标名回落到 file，与 navis-web 的 BankIcons.svg 兜底口径一致。
const body = computed(() => iconPaths[props.name] || iconPaths.file);
const svg = computed(() => `<svg width="${size.value}" height="${size.value}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${body.value}</svg>`);
</script>

<style scoped>
.vb-icon { display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; vertical-align: middle; }
</style>
