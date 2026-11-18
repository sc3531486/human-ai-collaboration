<template>
    <el-dialog v-model="visible" title="业务记录" width="70%" @closed="visible = false">
        <template #header>
            <div class="dialog-heading">
                <div>
                    <h2 id="restore-title">{{ title }}</h2>
                </div>
            </div>
        </template>
        <ul class="restore-trail">
            <li v-for="(row, index) in trail" :key="index">
                <vb-icon :name="row.completed ? 'check' : 'clock'" :size="13" />
                <span>{{ row.nodeName || row.nodeId }}</span>
            </li>
        </ul>
    </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { bus } from '@/core/bus.js';

const store = useWorkspaceStore();
const visible = ref(false);
const target = ref(null);

bus.on('restore:open', state => {
    if (!state) return;
    const trail = (state.history || state.trail || []).filter(Boolean);
    // 没有可还原的节点时不弹窗，避免弹一个空模态框遮住工作区。
    if (!trail.length) return store.toast('当前业务暂无可还原的历史节点', true);
    target.value = state;
    visible.value = true;
});

const trail = computed(() => (target.value?.history || target.value?.trail || []).filter(Boolean));
const title = computed(() => `${target.value?.bizNo || '业务'} · 界面还原`);
</script>
