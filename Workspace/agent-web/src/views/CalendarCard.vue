<template>
    <!-- 日历：只按浏览器本地时间渲染当月网格，不引入假期与便签数据。 -->
    <section class="vb-card">
        <header class="vb-card-head"><h2><vb-icon name="calendar" :size="16" />日历与便签</h2></header>
        <div class="vb-calendar-tools">
            <button class="vb-icon-btn" type="button" aria-label="上个月" @click="shift(-1)"><vb-icon name="chevron" :size="14" class="vb-flip" /></button>
            <b>{{ title }}</b>
            <button class="vb-icon-btn" type="button" aria-label="下个月" @click="shift(1)"><vb-icon name="chevron" :size="14" /></button>
            <button class="vb-mini-btn" type="button" @click="today">返回今天</button>
        </div>
        <div class="vb-calendar-week"><span>日</span><span>一</span><span>二</span><span>三</span><span>四</span><span>五</span><span>六</span></div>
        <div class="vb-calendar-grid">
            <button v-for="(cell, index) in cells" :key="index" class="vb-cal-cell" :class="cell.className" type="button">
                {{ cell.day }}
            </button>
        </div>
    </section>
</template>

<script setup>
import { ref, computed } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';

const cursor = ref(new Date());
const now = new Date();

const title = computed(() => `${cursor.value.getFullYear()} 年 ${cursor.value.getMonth() + 1} 月`);

const cells = computed(() => {
    const year = cursor.value.getFullYear();
    const month = cursor.value.getMonth();
    const lead = new Date(year, month, 1).getDay();
    const days = new Date(year, month + 1, 0).getDate();
    const list = [];
    for (let i = lead; i > 0; i--) list.push({ day: new Date(year, month, 1 - i).getDate(), className: 'muted' });
    for (let day = 1; day <= days; day++) {
        const isToday = now.getFullYear() === year && now.getMonth() === month && now.getDate() === day;
        list.push({ day, className: isToday ? 'today' : '' });
    }
    // 末行补齐到整周，网格不会出现缺格。
    const total = Math.ceil((lead + days) / 7) * 7;
    for (let i = lead + days; i < total; i++) {
        list.push({ day: new Date(year, month + 1, i - lead - days + 1).getDate(), className: 'muted' });
    }
    return list;
});

const shift = months => { cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + months, 1); };
const today = () => { cursor.value = new Date(); };
</script>
