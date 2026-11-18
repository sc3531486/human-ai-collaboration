<template>
    <!-- 账户查询 / 结果确认：素材 nodes/balance-result.js + views/completion.js 的 broadcast。 -->
    <vb-node-frame label="账户查询 / 结果确认" :title="policy.title || '确认余额查询结果'"
        :description="policy.description || '查询结果由核心接口返回；告知客户后确认办结。'">
        <vb-facts :rows="rows" />
        <div class="node-actions">
            <button class="primary-button" type="button" :disabled="readOnly" @click="complete">
                {{ readOnly ? '历史结果 · 只读' : '已告知客户，完成查询' }}
            </button>
        </div>
    </vb-node-frame>
</template>

<script setup>
import { computed } from 'vue';
import VbNodeFrame from '@/ui/VbNodeFrame.vue';
import VbFacts from '@/ui/VbFacts.vue';
import { submitNode } from '@/core/flow/actions.js';
import { video } from '@/core/video-widget.js';

const props = defineProps({ state: { type: Object, required: true }, readOnly: { type: Boolean, default: false } });
const policy = computed(() => props.state.nodePolicy || {});
const vars = computed(() => props.state.vars || {});
const rows = computed(() => [
    ['可用余额', vars.value.balanceResult], ['币种', vars.value.account_currency], ['账户状态', vars.value.account_status]
]);

/**
 * 办结：先把人工见证录像封存上去佐证，再推进流程。
 * ★ seal() 回执 fileSize === 0 时不得宣告成功（§3.4），如实提示未采集到画面。
 */
async function complete() {
    if (props.readOnly) return;
    const receipt = await video.record.seal();
    if (!receipt || receipt.fileSize === 0) throw new Error('该段未采集到画面，录像未封存成功');
    await submitNode(props.state, {});
}
void policy;
</script>
