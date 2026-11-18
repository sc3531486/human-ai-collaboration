<template>
    <!-- 右栏：首页看日历与公告，业务视图看视频服务与随行知识；两组按当前视图成对切换。 -->
    <aside class="vb-side">
        <section v-show="store.view === 'home'" class="vb-side-group">
            <CalendarCard />
            <NoticeCard />
        </section>

        <section v-show="store.view === 'business'" class="vb-side-group vb-business-side">
            <section class="vb-card">
                <header class="vb-card-head">
                    <h2><vb-icon name="video" :size="16" />视频服务</h2>
                    <span class="vb-pill neutral">{{ CALL_LABELS[videoState.connection] || '未连接' }}</span>
                </header>
                <!-- §2.9：视频画面由独立控件承担，这里只反射控件状态，不持有任何 video DOM -->
                <p class="vb-video-note">{{ videoNote }}</p>
            </section>

            <section class="vb-card vb-assist">
                <div class="vb-assist-tabs">
                    <button class="vb-assist-tab" type="button" :class="{ active: tab === 'chat' }" @click="tab = 'chat'">
                        <vb-icon name="message" :size="14" />文字聊天
                    </button>
                    <button class="vb-assist-tab" type="button" :class="{ active: tab === 'knowledge' }" @click="tab = 'knowledge'">
                        <vb-icon name="spark" :size="14" />知识随行
                    </button>
                </div>
                <div v-show="tab === 'chat'" class="vb-assist-pane">
                    <ul class="vb-chat-log">
                        <li v-if="!rows.length" class="vb-chat sys"><p>暂无客户消息</p></li>
                        <li v-for="(entry, index) in rows" :key="index" class="vb-chat" :class="{ sys: entry.role === 'SYSTEM', mine: entry.role === 'AGENT' }">
                            <strong>{{ entry.time || '—' }}</strong>
                            <p v-if="entry.role === 'SYSTEM'">{{ entry.text }}</p>
                            <p v-else><b>{{ sender(entry.role) }}</b>　{{ entry.text }}</p>
                        </li>
                    </ul>
                    <div class="vb-chat-input">
                        <input v-model="draft" type="text" :disabled="sending" placeholder="输入消息，按回车发送" aria-label="聊天输入" @keyup.enter="send">
                        <button type="button" :disabled="sending || !draft.trim()" aria-label="发送消息" @click="send"><vb-icon name="send" :size="15" /></button>
                    </div>
                </div>
                <div v-show="tab === 'knowledge'" class="vb-assist-pane">
                    <div class="vb-guidance"><span>当前建议</span><p>{{ guidance }}</p></div>
                    <div class="vb-knowledge-list">
                        <button class="vb-knowledge-item" type="button" @click="notReady"><vb-icon name="shield" :size="16" /><div><strong>转账授权合规要点</strong><small>身份核验 · 意愿确认 · 留痕要求</small></div></button>
                        <button class="vb-knowledge-item" type="button" @click="notReady"><vb-icon name="info" :size="16" /><div><strong>高风险交易处置口径</strong><small>风险等级判定与人工复核话术</small></div></button>
                        <button class="vb-knowledge-item" type="button" @click="notReady"><vb-icon name="archive" :size="16" /><div><strong>影像证据留存规范</strong><small>分段录制 · 截图 · 归档核验</small></div></button>
                    </div>
                </div>
            </section>
        </section>
    </aside>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';
import CalendarCard from './CalendarCard.vue';
import NoticeCard from './NoticeCard.vue';
import { useWorkspaceStore } from '@/stores/workspace.js';
import { video } from '@/core/video-widget.js';
import { report } from '@/core/bus.js';

const store = useWorkspaceStore();
const tab = ref('chat');
const draft = ref('');
const videoState = ref(video.getState());
let off = null;
onMounted(() => { off = video.on('state', next => { videoState.value = next; }); });
onBeforeUnmount(() => { if (off) off(); });

const CALL_LABELS = { idle: '未连接', connecting: '连接中', connected: '通话中', ended: '通话已结束', error: '通话异常' };

// 文字聊天只展示真实对话；节点日志不占用客户沟通区域。
const rows = computed(() => store.conversation);
const sending = ref(false);
const sender = role => (role === 'AGENT' ? '坐席' : role === 'CUSTOMER' ? '客户' : role === 'DIGITAL_HUMAN' ? '数字人' : '系统');
const guidance = computed(() => store.business?.nodePolicy?.description || '请按照当前节点提示办理。');
const videoNote = computed(() => {
    if (videoState.value.recording) return '正在录制三分屏画面';
    if (videoState.value.connection === 'connected') return '客户与坐席视频已连接';
    return '摄像头将在接听时请求授权';
});

async function send() {
    const text = draft.value.trim();
    if (!text || sending.value) return;
    sending.value = true;
    try { await store.sendConversation(text); draft.value = ''; }
    catch (error) { report('发送文字消息', error); }
    finally { sending.value = false; }
}
function notReady() { store.toast('演示环境暂未接入该能力', true); }
</script>
