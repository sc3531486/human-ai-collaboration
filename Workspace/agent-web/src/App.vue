<template>
    <!-- 端壳：只做布局与常驻容器（契约 §1）；业务节点内容全部由 views/ 与场景包承担。 -->
    <LoginView v-if="!authenticated" @authenticated="openWorkspace" />
    <div v-else class="vb-shell">
        <header class="vb-topbar">
            <div class="vb-brand">
                <span class="vb-logo"><vb-icon name="bank" :size="22" /></span>
                <span class="vb-brand-name">视频银行</span>
            </div>
            <label class="vb-search">
                <vb-icon name="search" :size="16" />
                <input v-model="globalSearch" type="search" placeholder="请输入交易代码/关键字" aria-label="全局搜索">
            </label>
            <div class="vb-topbar-tools">
                <button class="vb-topitem" type="button" @click="focusNotice"><vb-icon name="bell" :size="16" />公告</button>
                <span class="vb-workstate" :data-state="store.headerState">
                    <span class="vb-workstate-dot" :class="{ live: store.online, ring: store.headerState === 'ringing' }"></span>
                    <b>{{ headerLabel }}</b>
                    <em class="vb-timer">{{ headerTimer }}</em>
                </span>
                <div class="vb-user"><span class="vb-user-avatar">{{ identity.agentName.charAt(0) }}</span></div>
            </div>
        </header>

        <VideoBar v-if="store.callSession?.status === 'OPEN'" />

        <div class="vb-body">
            <!-- 左栏互斥：办理中显示办理人信息，其余显示坐席控制台（必须成对切换） -->
            <AgentPanel v-if="leftPanel === 'agent'" />
            <ApplicantPanel v-else />

            <StageView />

            <SideRail />
        </div>


        <CallRing />
        <RestoreDialog />
        <HistoryDialog />
        <ServiceSummaryDialog />
        <div class="vb-toast" :class="{ error: toastError }" role="status" v-show="toastText">{{ toastText }}</div>
    </div>

    <!-- §2.9 独立视频控件：overlay 浮层常驻容器——不参与业务布局（§2.9.1），
         生命周期不随业务节点/视图切换重建（§8.4-2）；pointer-events:none 由适配层把交互还给控件根。 -->
    <div ref="videoHost" class="vb-video-host" style="position: fixed; inset: 0; pointer-events: none; z-index: 2000;"></div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount, provide } from 'vue';
import { ElMessageBox } from 'element-plus';
import VbIcon from '@/ui/VbIcon.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { bus } from '@/core/bus.js';
import { identity, hasAgentSession, clearAgentSession } from '@/core/identity.js';
import { hms } from '@/core/format.js';
import { video } from '@/core/video-widget.js';
import VideoBar from '@/views/VideoBar.vue';
import AgentPanel from '@/views/AgentPanel.vue';
import ApplicantPanel from '@/views/ApplicantPanel.vue';
import StageView from '@/views/StageView.vue';
import SideRail from '@/views/SideRail.vue';
import CallRing from '@/views/CallRing.vue';
import RestoreDialog from '@/views/RestoreDialog.vue';
import HistoryDialog from '@/views/HistoryDialog.vue';
import ServiceSummaryDialog from '@/views/ServiceSummaryDialog.vue';
import LoginView from '@/views/LoginView.vue';

const store = useWorkspaceStore();
const authenticated = ref(hasAgentSession());
let initialized = false;
function leaveWorkspace() {
    store.dispose();
    clearAgentSession();
    for (const key of ['bank.online', 'bank.processId', 'bank.signInAt']) sessionStorage.removeItem(key);
    // 重建工作台，清除旧账号的状态与事件监听。
    window.location.reload();
}
bus.on('auth:logout', leaveWorkspace);
bus.on('auth:expired', leaveWorkspace);
bus.on('ui:customer-hangup', message => { void ElMessageBox.alert(message, '通话提示', { confirmButtonText: '知道了' }).catch(() => {}); });
const globalSearch = ref('');
const videoHost = ref(null);

/* ---- 左栏互斥：单一状态源，避免两处各写一遍导致同时占位或同时消失 ---- */
const leftPanel = ref('agent');
bus.on('layout:left-panel', isBusiness => { leftPanel.value = isBusiness ? 'applicant' : 'agent'; });

/* ---- 顶栏计时与状态文案 ---- */
const timerSeconds = ref(0);
bus.on('header:timer', value => { timerSeconds.value = value; });
const headerTimer = computed(() => hms(timerSeconds.value));
const HEADER_LABELS = { offline: '未签入', online: '就绪中', ringing: '振铃中', video: '视频中' };
const headerLabel = computed(() => HEADER_LABELS[store.headerState] || '未签入');

/* ---- Toast：保持与 navis-web 一致的时长语义（错误停留更久，便于抄录提示） ---- */
const toastText = ref('');
const toastError = ref(false);
let toastTimer = null;
bus.on('ui:toast', ({ message, error }) => {
    toastText.value = message;
    toastError.value = Boolean(error);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toastText.value = ''; }, error ? 7000 : 4000);
});

function focusNotice() {
    if (store.view !== 'home') store.home();
    bus.emit('home:focus-notice');
}

async function openWorkspace() {
    authenticated.value = true;
    await nextTick();
    await initializeWorkspace();
}

async function initializeWorkspace() {
    if (initialized) return;
    initialized = true;
    await store.init();
    // 只登记承载位，不预先创建视频窗口。接听策略由业务 store 在用户点击接听时触发。
    video.setHost(videoHost.value);
}
onMounted(() => { if (authenticated.value) void initializeWorkspace(); });
onBeforeUnmount(() => { video.setHost(null); if (initialized) store.dispose(); clearTimeout(toastTimer); });

provide('globalSearch', globalSearch);
</script>
