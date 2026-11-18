<template>
    <!--
      来电振铃横条：**当前唯一的接听入口**。
      出现时队列浮层与浮动等待钮已由 store 的互斥计算让位，不会同时出现两个入口。
    -->
    <div v-if="store.ringing" class="call-ring" role="alertdialog" aria-live="assertive">
        <span class="vb-call-ring-tag" aria-hidden="true"><vb-icon name="phone" :size="20" /></span>
        <div class="call-ring-body">
            <div class="call-ring-grid">
                <span class="call-ring-col"><i>时间</i><strong class="ring-wait">{{ waitLabel }}</strong></span>
                <span class="call-ring-col"><i>呼入渠道</i><strong>{{ ticket.channel || ticket.vars?.channel || '移动端' }}</strong></span>
                <span class="call-ring-col"><i>客户名称</i><strong>{{ ticket.customerName || ticket.vars?.customer_name || '客户' }}</strong></span>
            </div>
            <div class="call-ring-facts">
                <span class="level-badge" :class="levelClass">{{ ticket.levelName || '普通客户' }}</span>
                <strong>{{ metaLabel }}</strong>
                <span>技能组 <strong>{{ ticket.skillGroup || 'GENERAL' }}</strong></span>
                <span>金额 <strong>{{ money(ticket.amount) }}</strong></span>
                <span>流水号 <strong>{{ ticket.bizNo || '—' }}</strong></span>
            </div>
        </div>
        <div class="call-ring-actions">
            <button class="ring-reject" type="button" :disabled="busy" @click="reject">拒接</button>
            <button class="ring-answer" type="button" :disabled="busy" @click="answer">
                <vb-icon name="phone" :size="16" />一键接听
            </button>
        </div>
    </div>
</template>

<script setup>
import { computed, ref, onBeforeUnmount } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { money, hms } from '@/core/format.js';
import { bus, report } from '@/core/bus.js';

const store = useWorkspaceStore();
const waitSeconds = ref(0);
const busy = ref(false);

bus.on('ring:wait', value => { waitSeconds.value = value; });
const waitLabel = computed(() => hms(waitSeconds.value));
const ticket = computed(() => store.ringing || {});

// 还原 navis-web 的展示口径：渠道已在左侧独立成列，这里补归属网点，让窄条每个位置都承载新信息。
const businessLabels = { BALANCE: '账户查询', TRANSFER: '转账汇款', PASSWORD_RESET: '密码重置' };
const metaLabel = computed(() => {
    const biz = businessLabels[ticket.value.bizType] || ticket.value.vars?.trade_name || '业务办理';
    const branch = ticket.value.vars?.branch_name;
    return branch ? `${biz} · ${branch}` : biz;
});
const levelClass = computed(() => (ticket.value.level === 'PRIVATE_BANKING' ? 'private' : ticket.value.level === 'VIP' ? 'vip' : ''));

async function answer() {
    busy.value = true;
    try { await store.answerCall(ticket.value.processId); }
    catch (error) { report('接听来电', error); }   // 带码呈现（含值守类前置校验原因），横条保持可再试
    finally { busy.value = false; }
}
async function reject() {
    busy.value = true;
    try { await store.rejectCall(ticket.value.processId); }
    catch (error) { report('拒接来电', error); }
    finally { busy.value = false; }
}

onBeforeUnmount(() => { waitSeconds.value = 0; });
</script>
