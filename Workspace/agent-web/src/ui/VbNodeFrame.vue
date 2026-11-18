<template>
    <!-- 节点页外框：替代 node_ui.js 的 NodeUI.frame，保留 node-page 系列 class。 -->
    <article class="node-page">
        <header class="node-page-heading">
            <span class="node-kicker">{{ label }}</span>
            <h2>{{ title }}</h2>
            <p>{{ description }}</p>
        </header>
        <slot />
    </article>
</template>

<script setup>
defineProps({
    label: { type: String, default: '' },
    title: { type: String, default: '' },
    description: { type: String, default: '' }
});
</script>
