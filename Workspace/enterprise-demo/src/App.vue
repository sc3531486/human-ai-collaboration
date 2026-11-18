<script setup>
/*
 * 企业网银渠道宿主端壳：只做渠道自有布局与自有路由。
 * SDK 挂载在 BankView 的容器里——宿主决定容器出现的位置与时机，
 * 页面导航、顶栏、footer 全部属于渠道方（契约 §6.1：SDK 只拥有容器，不拥有页面）。
 */
import { computed, onMounted, onBeforeUnmount, ref } from 'vue';
import HomeView from './views/HomeView.vue';
import BankView from './views/BankView.vue';

const routes = [
    { id: 'home', name: '渠道首页', component: HomeView },
    { id: 'bank', name: '远程视频银行', component: BankView },
];

const currentId = ref('home');

function routeFromHash() {
    const hash = (window.location.hash || '').replace(/^#\/?/, '');
    return routes.some((r) => r.id === hash) ? hash : 'home';
}

function onHashChange() {
    currentId.value = routeFromHash();
}

function navigate(id) {
    window.location.hash = '#/' + id;
}

onMounted(() => {
    currentId.value = routeFromHash();
    window.addEventListener('hashchange', onHashChange);
});
onBeforeUnmount(() => window.removeEventListener('hashchange', onHashChange));

const current = computed(() => routes.find((r) => r.id === currentId.value) || routes[0]);
</script>

<template>
    <div class="host-shell" :class="{ 'host-shell-bank': currentId === 'bank' }">
        <header v-if="currentId !== 'bank'" class="host-header">
            <div class="host-brand">
                <span class="logo">企</span>
                <div>
                    晨曦企业网银
                    <small>对公网银 · 远程视频银行渠道示例</small>
                </div>
            </div>
            <nav class="host-nav" aria-label="渠道自有导航">
                <button
                    v-for="route in routes"
                    :key="route.id"
                    :class="{ active: route.id === currentId }"
                    @click="navigate(route.id)"
                >
                    {{ route.name }}
                </button>
            </nav>
            <div class="host-user">企业操作员 · 王财务（渠道方自有会话）</div>
        </header>

        <main :class="currentId === 'bank' ? 'sdk-page' : 'host-main'">
            <component :is="current.component" :key="current.id" />
        </main>

        <footer v-if="currentId !== 'bank'" class="host-footer">
            晨曦企业网银 · 企业服务专线 400-800-9527
        </footer>
    </div>
</template>
