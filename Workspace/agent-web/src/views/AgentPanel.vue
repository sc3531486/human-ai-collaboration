<template>
    <!-- 左栏 · 坐席控制台（与办理人信息互斥占用同一格） -->
    <aside class="vb-agentbar">
        <section class="vb-agent-card">
            <div class="vb-agent-avatar">{{ identity.agentName.charAt(0) }}</div>
            <div class="vb-agent-name">
                <b>{{ identity.agentName }}</b><span>-</span><b>{{ identity.employeeNo || store.agentNumber }}</b>
            </div>
            <p class="vb-agent-org">{{ identity.centerName }} · {{ identity.institutionName }} · {{ identity.positionName }}</p>
            <p class="vb-agent-org">登录账号：{{ identity.loginName || '—' }}</p>
            <div class="vb-agent-actions">
                <button class="vb-sign-btn" type="button" :disabled="store.online" @click="store.signIn()">
                    <vb-icon name="signin" :size="15" />签入
                </button>
                <button class="vb-sign-btn" type="button" :disabled="!store.online" @click="store.signOut()">
                    <vb-icon name="logout" :size="15" />签出
                </button>
            </div>
            <button class="vb-sign-btn" type="button" style="width:100%;margin-top:10px" @click="store.logout()"><vb-icon name="logout" :size="15" />签退并退出系统</button>
            <div class="vb-agent-kpi">
                <div><strong>{{ store.stats.servedCount }}</strong><span>已服务客户数</span></div>
                <div><strong>{{ store.queueCandidates.length }}</strong><span>当前排队人数</span></div>
            </div>
            <p class="vb-agent-tip">{{ store.online ? '已签入，正在等待客户进线…' : '温馨提示：您现在是未签入状态，请签入后等待系统任务！' }}</p>
        </section>

        <section class="vb-agent-stats">
            <h3>当日服务统计</h3>
            <div class="vb-stat-grid">
                <div class="vb-stat"><vb-icon name="signin" :size="15" /><small>当日签入时长</small><b>{{ hms(store.stats.signInSeconds) }}</b></div>
                <div class="vb-stat"><vb-icon name="clock" :size="15" /><small>当日小休时长</small><b>{{ hms(store.stats.breakSeconds) }}</b></div>
                <div class="vb-stat"><vb-icon name="clock" :size="15" /><small>当日办理时长</small><b>{{ hms(store.stats.serviceSeconds) }}</b></div>
                <div class="vb-stat"><vb-icon name="chart" :size="15" /><small>当日办理均长</small><b>{{ hms(store.stats.averageServiceSeconds) }}</b></div>
            </div>
            <div class="vb-stat-tiles">
                <div class="vb-stat-tile mint"><vb-icon name="archive" :size="20" /><div><b>{{ store.stats.servedCount }}</b><small>当日已服务次数</small></div></div>
                <div class="vb-stat-tile amber"><vb-icon name="check" :size="20" /><div><b>{{ store.stats.successCount }}</b><small>当日交易成功数</small></div></div>
            </div>
        </section>
    </aside>
</template>

<script setup>
import VbIcon from '@/ui/VbIcon.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { identity } from '@/core/identity.js';
import { hms } from '@/core/format.js';

const store = useWorkspaceStore();
</script>
