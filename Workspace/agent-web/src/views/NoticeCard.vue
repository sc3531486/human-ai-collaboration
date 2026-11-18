<template>
    <!-- 系统公告：按级别分色；接口不可用时列表为空并如实提示。 -->
    <section class="vb-card vb-notice-card">
        <header class="vb-card-head"><h2><vb-icon name="bell" :size="16" />系统公告</h2></header>
        <ul class="vb-notice-list">
            <li v-if="!store.notices.length" class="vb-notice"><strong>公告服务暂不可用</strong></li>
            <li v-for="item in store.notices" :key="item.id" class="vb-notice">
                <div class="vb-notice-head">
                    <span class="vb-notice-level" :class="tone(item.level).className">{{ tone(item.level).label }}</span>
                    <time>{{ noticeTime(item.publishTime) }}</time>
                </div>
                <strong>{{ item.title }}</strong>
                <p v-if="item.content">{{ item.content }}</p>
            </li>
        </ul>
    </section>
</template>

<script setup>
import VbIcon from '@/ui/VbIcon.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { noticeTime } from '@/core/format.js';
import { bus, report } from '@/core/bus.js';

const store = useWorkspaceStore();
const LEVELS = { WARN: ['warning', '重要'], WARNING: ['warning', '重要'], DANGER: ['danger', '紧急'], ERROR: ['danger', '紧急'] };
const tone = level => { const [className, label] = LEVELS[level] || ['', '通知']; return { className, label }; };

bus.on('home:focus-notice', () => {
    // 滚动到公告卡片：由 App.vue 的顶栏公告入口触发。
    const node = document.querySelector('.vb-notice-card');
    if (node) node.scrollIntoView({ behavior: 'smooth', block: 'center' });
    else {
        // 卡片常驻（SideRail v-show 切换）：查不到＝装配关系被改动，如实上报而非静默跳过。
        report('公告卡片定位', new Error('未找到 .vb-notice-card，顶栏公告入口未生效'), { sticky: true });
    }
});
</script>
