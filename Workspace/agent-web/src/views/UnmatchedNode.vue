<template>
    <!-- 路由未命中：展示「当前节点尚未配置界面」+ 重新匹配按钮，不改变当前业务状态。 -->
    <div class="route-error">
        <vb-icon name="info" :size="35" />
        <h2>当前节点尚未配置界面</h2>
        <p>{{ reason || '—' }}<br>当前业务状态已保留，可刷新重试。</p>
        <button class="secondary-button" type="button" @click="retry">重新匹配</button>
    </div>
</template>

<script setup>
import VbIcon from '@/ui/VbIcon.vue';
import * as sync from '@/core/flow/sync.js';
import { report } from '@/core/bus.js';

defineProps({ reason: { type: String, default: '' } });

async function retry() {
    try { await sync.refresh(); }
    catch (error) {
        // 刷新失败保留原界面（不清空状态），但必须带码告知——用户主动点了按钮，不能无响应。
        report('节点重新匹配', error);
    }
}
</script>
