<template>
    <!-- 左栏 · 办理人信息：随当前业务快照更新，缺失显示占位符。 -->
    <aside class="vb-applicant">
        <header class="vb-applicant-head"><h2>办理人信息</h2></header>
        <div class="vb-applicant-name">
            <strong>{{ idFront.name || vars.customer_name || '—' }}</strong>
            <div>
                <button class="vb-mini-btn" type="button" @click="hint">客户标签</button>
                <button class="vb-mini-btn" type="button" @click="hint">查看影像</button>
            </div>
        </div>
        <dl class="vb-field-list">
            <div><dt>性别</dt><dd>{{ idFront.gender || vars.gender || '—' }}</dd></div>
            <div><dt>客户号</dt><dd><span>{{ vars.cust_id || vars.customer_id || '—' }}</span><i class="vb-flag" :class="noFlagClass">{{ noFlagText }}</i></dd></div>
            <div><dt>证件类型</dt><dd>{{ certType }}</dd></div>
            <div><dt>证件号码</dt><dd>{{ idFront.certNoMasked || vars.cert_no_masked || vars.customer_cert_masked || '—' }}</dd></div>
            <div><dt>有效期</dt><dd><span>{{ certValidity || '—' }}</span><i v-if="validity" class="vb-flag" :class="validity[1]">{{ validity[0] }}</i></dd></div>
            <div><dt>手机号码</dt><dd>{{ vars.phone_masked || vars.customer_mobile_masked || '—' }}</dd></div>
            <div><dt>联网核查</dt><dd><i class="vb-flag" :class="networkCheck[1]">{{ networkCheck[0] }}</i></dd></div>
            <div><dt>任务流水</dt><dd>{{ store.business?.bizNo || '—' }}</dd></div>
        </dl>
        <div class="vb-applicant-actions">
            <button class="vb-teal-btn" type="button" @click="hint2"><vb-icon name="user" :size="15" />人脸比对</button>
        </div>
        <div class="vb-bankcard" aria-hidden="true"><span>BANK OF VIDEO</span></div>
        <section class="vb-mini-block"><h3>客户服务情况</h3><p>{{ serviceState }}</p></section>
        <section class="vb-mini-block">
            <h3>客户呼入信息</h3>
            <dl>
                <div><dt>进线渠道</dt><dd>{{ vars.channel || '—' }}</dd></div>
                <div><dt>归属网点</dt><dd>{{ vars.branch_name || '—' }}</dd></div>
                <div><dt>设备编号</dt><dd>{{ vars.device_id || '—' }}</dd></div>
                <div><dt>终端柜员</dt><dd>{{ vars.teller_id || '—' }}</dd></div>
            </dl>
        </section>
    </aside>
</template>

<script setup>
import { computed } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { certLabel } from '@/core/format.js';

const store = useWorkspaceStore();
const vars = computed(() => store.business?.vars || {});
const idFront = computed(() => vars.value.idFrontOcr || {});
const certType = computed(() => certLabel(vars.value.cert_type || (idFront.value.certNoMasked ? '居民身份证' : '')));
const certValidity = computed(() => {
    const { validFrom, validTo } = vars.value.idBackOcr || {};
    return validFrom && validTo ? `${validFrom} 至 ${validTo}` : vars.value.cert_validity;
});

// 客户号核实状态与证件联网核查是不同结果。
const noFlagClass = computed(() => (vars.value.cust_id_status === 'VERIFIED' ? 'ok' : 'error'));
const noFlagText = computed(() => (vars.value.cust_id_status === 'VERIFIED' ? '已核实' : '未核实'));
const VALIDITY = { VALID: ['未到期', 'warn'], EXPIRING: ['即将到期', 'warn'], EXPIRED: ['已过期', 'error'] };
const validity = computed(() => VALIDITY[vars.value.cert_validity_status]);
const networkCheck = computed(() => {
    if (vars.value.onlinePassed === true) return ['核查通过', 'ok'];
    if (vars.value.onlinePassed === false) return ['核查未通过', 'error'];
    return vars.value.network_check_status === 'CHECKED' ? ['已核查', 'ok'] : ['未核查', 'warn'];
});

/**
 * 客户服务情况：按当前节点与真实流程变量描述，**不编造客户侧状态**。
 * 未登记的节点按节点名兜底描述，因此后端新增节点也不需要改这里。
 */
const serviceState = computed(() => {
    const state = store.business;
    if (!state) return '—';
    if (state.processStatus === 'COMPLETED') return '业务已办结';
    if (state.nodePolicy?.video === true) return '待坐席视频核实身份与业务要素';
    if (vars.value.risk_decision) return vars.value.risk_decision;
    return `正在办理「${state.nodeName || '当前节点'}」`;
});

const hint = () => store.toast('演示环境暂未接入该能力', true);
const hint2 = () => store.toast('人脸比对需接入影像平台', true);
</script>
