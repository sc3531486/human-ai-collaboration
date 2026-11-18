<template>
    <!-- 业务视图：办理进度 + 路由出的节点组件 + 只读历史还原区。节点内容全部来自场景包或通用兜底。 -->
    <div class="vb-stage-business">
        <div class="vb-process">
            <div class="vb-process-head">
                <strong>业务办理进度</strong>
                <span class="vb-connection">{{ CONNECTION_LABELS[store.connection] || store.connection }}</span>
            </div>
            <vb-steps :steps="steps" :completed="completed" :active-node-id="store.business?.nodeId || ''"
                :alert="handover" @restore="openRestore" />
        </div>

        <div v-show="handover" class="vb-process-alert">
            <vb-icon name="info" :size="16" />
            <div>
                <strong>人工视频核验</strong>
                <span>请核验客户身份与业务信息。</span>
            </div>
        </div>

        <!-- 业务视图不使用 v-if 之外的方式切换：切换业务**不销毁音视频容器**（音视频归独立控件持有） -->
        <section class="vb-node" v-show="historyNode === null" aria-live="polite">
            <component v-if="activeComponent" :is="activeComponent" v-bind="activeProps" />
            <UnmatchedNode v-else-if="unmatched" :reason="unmatchedReason" />
        </section>

        <!-- 历史还原：只读，不叠加聊天/日志/其他节点摘要 -->
        <InlineNodeHistory v-if="historyNode" :node-id="historyNode" @close="historyNode = null" />
    </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';
import VbSteps from '@/ui/VbSteps.vue';
import UnmatchedNode from './UnmatchedNode.vue';
import InlineNodeHistory from './InlineNodeHistory.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { activeComponent, activeProps, unmatched, unmatchedReason } from '@/core/flow/router.js';

const store = useWorkspaceStore();
const historyNode = ref(null);

const CONNECTION_LABELS = {
    connecting: '正在同步', live: '实时同步中', polling: '重连中 · 轮询同步', offline: '连接中断，正在重试'
};

const steps = computed(() => (store.business?.steps || [])
    .filter(step => step.agentVisible !== false)
    .map(step => ({ nodeId: step.nodeId, nodeName: step.nodeName })));
const completed = computed(() => {
    const done = (store.business?.history || []).filter(row => row.completed).map(row => row.nodeId);
    // 办结时把终点也算作已完成，时间轴上才不会出现"永远差最后一步"。
    if (store.business?.processStatus === 'COMPLETED') done.push('end');
    return done;
});
// 人工介入节点在时间轴上以红色告警呈现。
const handover = computed(() => store.business?.processStatus === 'RUNNING' && store.business?.nodePolicy?.video === true);

function openRestore(nodeId) { historyNode.value = nodeId; }
</script>
