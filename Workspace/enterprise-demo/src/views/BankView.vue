<script setup>
/*
 * 远程视频银行：SDK 挂载页。
 *
 * mount(el, config) —— SDK 只拥有 #sdk-container 这一块 DOM（契约 §6.1）：
 * 本页的标题、返回按钮、容器边框与状态栏全部是渠道方的，SDK 不感知。
 * 离开本页（路由切换）由宿主调用 destroy()；宿主 navigate.close 回调也能关闭容器。
 *
 * mount 之后必须调 startBusiness()（README §1 / §2）：mount 只建壳，流程绑定与
 * 实时通道全在 startBusiness 里。少了这一步，页面上第一个节点的表单仍会显示
 * —— 那是数字人对话直接调 nodeDialog 渲染的结果 —— 但服务端后续推送的节点
 * 状态会静默丢弃，表现为「坐席已授权、客户侧却不弹签名框」。两者不同源，
 * 所以必须两件都做，不能只做 mount。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { loadSdk } from '../core/sdk-loader.js';
import { buildBridge } from '../core/bridge-state.js';

const container = ref(null);
const status = ref('loading'); // loading | mounted | closed | error
const error = ref('');

let instance = null;
let disposed = false;

function closeSdk(reason) {
    if (instance) {
        try { instance.destroy(); } catch { /* 已销毁 */ }
        instance = null;
    }
    status.value = 'closed';
    error.value = reason || '';
}

async function mountSdk() {
    status.value = 'loading';
    error.value = ''
    try {
        const sdk = await loadSdk();
        if (disposed) return;
        // config.bridge 优先于 window.VBBankingBridge（contract.md §0）。
        instance = sdk.api.mount(container.value, {
            apiBase: '',
            channel: 'enterprise-web',
            channelId: 'ent-web-001',
            tenantId: 'tenant-demo',
            customerId: 'cust-0001',
            customerName: '晨曦集团 · 王财务',
            // 渠道以配置方式提供数字人资源；SDK 不依赖宿主根目录的 /images。
            avatarUrl: '/images/chenxi-idle.gif',
            avatarStillUrl: '/images/chenxi-still.png',
            speakingAvatarUrl: '/images/chenxi-speaking.gif',
            chatHome: true,
            // 控件是独立制品，渠道 SDK 仅通过 URL 契约加载，绝不引入其源码。
            widgetUrl: '/vendor/video-widget/vb-video.umd.js?v=media-user-v13',
            widgetCssUrl: '/vendor/video-widget/vb-video.css',
            bridge: buildBridge({
                // 宿主路由能力：渠道方收到 close 后按自己的方式收起容器（SDK 不控制宿主路由）。
                onClose: () => closeSdk('宿主 navigate.close 调用'),
                onNavigate: (target) => { window.location.hash = '#/' + target; },
            }),
            onEvent: (type) => {
                if (type === 'destroyed') closeSdk('');
            },
        });
        // mount 只建壳：流程绑定（sync.start）与 STOMP 实时通道都在 startBusiness 里。
        // 缺了它，数字人对话仍能直连渲染第一张表单，但后续节点推送全部丢失。
        // 桩件 SDK（产物缺失时的降级替身）没有 startBusiness，按契约只在有该方法时调用。
        if (typeof instance.startBusiness === 'function') {
            await instance.startBusiness();
        } else {
            console.warn('[BankView] SDK 未提供 startBusiness()：当前为桩件降级模式，实时通道不会建立。');
        }
        if (disposed) return;
        status.value = 'mounted';
    } catch (err) {
        status.value = 'error';
        error.value = err && err.message ? err.message : String(err);
    }
}

onMounted(mountSdk);
onBeforeUnmount(() => {
    disposed = true;
    if (instance) {
        try { instance.destroy(); } catch { /* 忽略重复销毁 */ }
        instance = null;
    }
});
</script>

<template>
    <div class="sdk-page-root">
        <div ref="container" id="sdk-container" class="sdk-container"></div>
        <div v-if="status === 'error'" class="sdk-load-error">
            <strong>远程服务暂时无法加载</strong><p>{{ error }}</p><button class="btn" @click="mountSdk">重新连接</button>
        </div>
    </div>
</template>
