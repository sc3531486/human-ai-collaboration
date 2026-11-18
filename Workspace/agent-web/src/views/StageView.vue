<template>
    <!-- 中栏：标签切换 + 首页/业务两个不随场景增长的视图。 -->
    <main class="vb-stage">
        <nav class="vb-tabs">
            <button class="vb-tab" type="button" :class="{ active: store.view === 'home' }" @click="store.home()">首页</button>
            <button v-show="store.businessTabVisible" class="vb-tab" type="button" :class="{ active: store.view === 'business' }" @click="store.gotoBusiness()">
                <span>{{ store.business?.processStatus === 'COMPLETED' ? '办理结果' : '当前业务' }}</span>
                <!-- 「×」＝结束本次业务：必须终结实例，只切回首页会让实例滞留占用待接队列 -->
                <span class="vb-tab-close" role="button" aria-label="结束本次业务" @click.stop="store.finish()">
                    <vb-icon name="close" :size="12" />
                </span>
            </button>
        </nav>
        <div class="vb-stage-body">
            <HomeView v-show="store.view === 'home'" />
            <BusinessView v-show="store.view === 'business'" />
        </div>
    </main>
</template>

<script setup>
import VbIcon from '@/ui/VbIcon.vue';
import HomeView from './HomeView.vue';
import BusinessView from './BusinessView.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';

const store = useWorkspaceStore();
</script>
