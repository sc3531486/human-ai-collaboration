<template>
    <!-- 视频工具条：通话中显示；未接入的能力明确提示而不是静默无响应。 -->
    <div class="vb-videobar">
        <div class="vb-videobar-tools">
            <button class="vb-videotool" type="button" @click="toggle('audio')">
                <vb-icon name="mic" :size="18" /><span>静音</span>
            </button>
            <button class="vb-videotool" type="button" @click="toggle('video')">
                <vb-icon name="video" :size="18" /><span>暂停画面</span>
            </button>
            <button class="vb-videotool" type="button" @click="hold">
                <vb-icon name="pause" :size="18" /><span>{{ holdOn ? '恢复' : '保持' }}</span>
            </button>
            <button class="vb-videotool" type="button" :class="{ active: voiceOnly }" @click="takeover">
                <vb-icon name="volume" :size="18" /><span>{{ voiceOnly ? '语音接管中' : '语音' }}</span>
            </button>
            <button class="vb-videotool" type="button" @click="hint('投屏协作能力未接入，请联系科技部门开通')"><vb-icon name="screen" :size="18" /><span>投屏</span></button>
            <button class="vb-videotool" type="button" @click="hint('演示文稿能力未接入，请联系科技部门开通')"><vb-icon name="deck" :size="18" /><span>演示文稿</span></button>
            <button class="vb-videotool" type="button" @click="hint('屏幕共享能力未接入，请联系科技部门开通')"><vb-icon name="share" :size="18" /><span>请求共享</span></button>
            <button class="vb-videotool" type="button" @click="hint('专家邀请能力未接入，请联系科技部门开通')"><vb-icon name="expert" :size="18" /><span>邀请专家</span></button>
            <button class="vb-videotool" type="button" @click="hint('电话银行知识库未接入，请联系科技部门开通')"><vb-icon name="book" :size="18" /><span>电话银行知识库</span></button>
        </div>
        <div class="vb-videobar-state">
            <span class="vb-videobar-live"><i></i>视频中 <b>{{ callTimer }}</b></span>
            <button class="vb-videotool danger" type="button" @click="hangup"><vb-icon name="phoneOff" :size="18" /><span>结束通话</span></button>
        </div>
    </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';
import { video } from '@/core/video-widget.js';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { hms } from '@/core/format.js';
import { report } from '@/core/bus.js';

const store = useWorkspaceStore();
const holdOn = ref(false);
const voiceOnly = ref(false);
const seconds = ref(0);
let timer = null;

const callTimer = computed(() => hms(seconds.value));

onMounted(() => {
    const started = Date.now();
    timer = setInterval(() => { seconds.value = Math.floor((Date.now() - started) / 1000); }, 1000);
});
onBeforeUnmount(() => clearInterval(timer));

async function toggle(kind) {
    try { await video.toggle(kind); }
    catch (error) { report('音视频开关切换', error); }   // 带码呈现：控件未接入/命令失败不许无响应
}
function hold() {
    holdOn.value = !holdOn.value;
    // 保持只切换本地通话标记，既不推进流程节点也不打断客户侧音视频。
    store.toast(holdOn.value ? '已进入保持状态，请及时恢复通话' : '已恢复通话');
}
/**
 * 语音接管开关：选择由服务端生效后为准，本地不做乐观翻转，避免坐席以为已切换而客户看到的仍是数字人。
 */
async function takeover() {
    if (!store.business?.processId) return store.toast('当前没有进行中的业务', true);
    try {
        await video.takeover(voiceOnly.value ? 'av' : 'audio');
        voiceOnly.value = !voiceOnly.value;
        store.toast(voiceOnly.value ? '已切换为语音接管，客户保持数字人画面' : '已切换为音视频接管，客户可见坐席画面');
    } catch (error) { report('语音接管切换', error); }   // 带码呈现：本地形态不翻转，与服务端实际状态保持一致
}
async function hangup() {
    await store.hangup();
    voiceOnly.value = false;
    holdOn.value = false;
}
function hint(message) { store.toast(message, true); }
</script>
