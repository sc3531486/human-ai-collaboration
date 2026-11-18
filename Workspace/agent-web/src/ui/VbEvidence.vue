<template>
    <!-- 影像要素：URL 必须命中 /records/<安全字符> 才渲染，防止任意 URL 注入（§3.7 红线）。 -->
    <figure class="verification-photo">
        <figcaption>{{ title }}</figcaption>
        <a v-if="safe" :href="safe" target="_blank" rel="noopener">
            <img :src="safe" :alt="title">
        </a>
        <div v-else class="verification-photo-empty">等待客户提交</div>
        <small v-if="note">{{ note }}</small>
    </figure>
</template>

<script setup>
import { computed } from 'vue';
import { safeRecordUrl } from '@/core/format.js';

const props = defineProps({
    title: { type: String, default: '' },
    url: { type: String, default: '' },
    note: { type: String, default: '' }
});
const safe = computed(() => safeRecordUrl(props.url));
</script>
