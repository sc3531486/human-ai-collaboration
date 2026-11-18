<template>
    <!-- 待接任务浮层：振铃期间由 store 的互斥计算隐藏，保证同一时刻只有一个接听入口。 -->
    <div v-if="store.queuePopVisible" class="vb-queue-pop">
        <header><strong>待接任务</strong><span>{{ store.queueCandidates.length }}</span></header>
        <div class="vb-queue-list">
            <div v-if="!store.queueCandidates.length" class="empty-state compact">
                <vb-icon name="headset" :size="30" /><p>暂时没有等待客户</p><small>客户进线后会自动显示</small>
            </div>
            <div v-for="item in store.queueCandidates" :key="item.processId" class="queue-item">
                <div class="queue-item-header">
                    <strong>{{ item.vars?.customer_name || '客户' }}</strong><span class="pill warning">待接听</span>
                </div>
                <p>{{ item.vars?.channel || '移动端' }} · {{ item.nodeName }}</p>
                <button type="button" :disabled="busy === item.processId" @click="accept(item)">
                    <vb-icon name="phone" :size="14" />接听业务
                </button>
            </div>
        </div>
        <div class="vb-queue-foot"><vb-icon name="info" :size="13" />按进线时间排序 · 接听后绑定业务</div>
    </div>
</template>

<script setup>
import { ref } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { report } from '@/core/bus.js';

const store = useWorkspaceStore();
const busy = ref('');

/**
 * 队列卡里的接听走 claimable=true：这类工单可能没有对应的振铃来电，
 * 遇到 404/409 才回落到 /accept 认领（store.answerCall 内部同一判据）。
 */
async function accept(item) {
    busy.value = item.processId;
    try { await store.answerCall(item.processId, true); }
    catch (error) { report('接听队列工单', error); }   // 带码呈现，卡片保持可重试
    finally { busy.value = ''; }
}
</script>
