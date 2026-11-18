<template>
    <!-- 首页：可办交易 + 交易情况列表。可办交易唯一来源是后端接口（契约 §4），失败带码上报、列表如实为空。 -->
    <div class="vb-stage-home">
        <section class="vb-block">
            <header class="vb-block-head"><h2>可办交易</h2></header>
            <div class="vb-trade-grid">
                <button v-for="(trade, index) in trades" :key="trade.tradeCode" class="vb-trade" type="button" @click="start(trade)">
                    <span class="tile-icon" :class="tones[index % tones.length]"><vb-icon :name="trade.icon || 'file'" :size="20" /></span>
                    <b>{{ trade.tradeCode }}-{{ trade.tradeName }}</b>
                    <small>{{ trade.description || '视频银行可办业务' }}</small>
                </button>
                <div v-if="!trades.length" class="empty-state compact"><p>暂无可办交易</p></div>
            </div>
        </section>

        <section class="vb-block vb-history-block">
            <header class="vb-block-head">
                <h2>交易情况列表</h2>
                <div class="vb-block-tools">
                    <label class="vb-inline-search"><vb-icon name="search" :size="14" />
                        <input v-model="store.keyword" placeholder="搜索流水号 / 客户" aria-label="搜索业务记录" @input="onSearch">
                    </label>
                    <button class="vb-icon-btn" type="button" aria-label="刷新业务记录" @click="store.loadHistory()">
                        <vb-icon name="refresh" :size="16" />
                    </button>
                </div>
            </header>
            <div class="vb-table-scroll">
                <table class="vb-table">
                    <thead><tr><th>序号</th><th>交易流水</th><th>交易办理时间</th><th>交易结果</th><th>接入渠道</th><th>客户证件类型</th><th>客户</th><th>操作</th></tr></thead>
                    <tbody>
                        <tr v-if="!items.length"><td colspan="8" class="vb-table-empty">暂无匹配的业务记录</td></tr>
                        <tr v-for="(item, index) in items" :key="item.biz_no || index">
                            <td>{{ (store.page - 1) * store.pageSize + index + 1 }}</td>
                            <td><strong>{{ item.biz_no }}</strong><small>{{ tradeName(item) }}</small></td>
                            <td>{{ stamp(item.created_time) || '—' }}</td>
                            <td><span class="vb-result" :class="status(item)[0]"><vb-icon :name="status(item)[1]" :size="13" />{{ status(item)[2] }}</span></td>
                            <td>{{ item.channel || '—' }}</td>
                            <td>{{ certLabel(item.cert_type) }}</td>
                            <td><strong>{{ item.customer_name || '客户' }}</strong><small v-if="item.customer_no">{{ item.customer_no }}</small></td>
                            <td class="vb-ops">
                                <button class="text-button" type="button" @click="store.openHistoryDetail(item.biz_no)">详情</button>
                                <button class="text-button" type="button" @click="store.openHistoryDetail(item.biz_no, 'image')">影像调阅</button>
                                <button class="text-button" type="button" @click="store.openHistoryDetail(item.biz_no, 'video')">视频调阅</button>
                            </td>
                        </tr>
                    </tbody>
                </table>
            </div>
            <div class="vb-pager">
                <span>共 {{ store.historyLegacy ? store.history.length : store.historyTotal }} 条</span>
                <select v-model.number="store.pageSize" class="vb-pagesize" aria-label="每页条数" @change="store.loadHistory(1)">
                    <option :value="5">5条/页</option><option :value="10">10条/页</option><option :value="20">20条/页</option>
                </select>
                <nav class="vb-pager-nav">
                    <button type="button" :disabled="store.page === 1" @click="store.loadHistory(1)">首页</button>
                    <button type="button" :disabled="store.page === 1" @click="store.loadHistory(store.page - 1)">&lt; 上一页</button>
                    <span class="vb-page-now">{{ store.page }} / {{ pageCount }}</span>
                    <button type="button" :disabled="store.page >= pageCount" @click="store.loadHistory(store.page + 1)">下一页 &gt;</button>
                    <button type="button" :disabled="store.page >= pageCount" @click="store.loadHistory(pageCount)">尾页</button>
                </nav>
            </div>
        </section>
    </div>
</template>

<script setup>
import { computed } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { stamp, certLabel } from '@/core/format.js';

const store = useWorkspaceStore();
const tones = ['mint', 'blue', 'amber', 'sand'];

const trades = computed(() => store.trades);
// 本地切片模式下由当前页切；服务端分页模式下接口已按页返回。
const items = computed(() => (store.historyLegacy
    ? store.history.slice((store.page - 1) * store.pageSize, store.page * store.pageSize)
    : store.history));
const pageCount = computed(() => Math.max(1, Math.ceil((store.historyLegacy ? store.history.length : store.historyTotal) / store.pageSize)));

let searchTimer = null;
function onSearch() {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => store.loadHistory(1), 300);
}

// 交易结果按语义分色：办结为成功、办理失败与流程终止同属失败、进行中的单独用待办色。
const STATUSES = {
    COMPLETED: ['ok', 'check', '成功'], FAILED: ['bad', 'close', '失败'],
    REJECTED: ['bad', 'close', '已终止'], PROCESSING: ['mid', 'clock', '办理中']
};
const status = item => STATUSES[item.status] || ['plain', 'info', item.status || '待确认'];
// 交易名称优先取接口新增字段，旧接口按业务类型回落。
const tradeName = item => (item.trade_name
    ? `${item.trade_name}${item.trade_code ? ` · ${item.trade_code}` : ''}`
    : item.biz_type === 'BALANCE' ? '余额查询' : '转账汇款');

/** 定义键为空的交易只做未接入提示，不静默无响应。 */
async function start(trade) {
    if (!trade.processDefinitionKey) return store.toast('演示环境暂未接入该交易品种', true);
    await store.startTrade(trade);
}
</script>
