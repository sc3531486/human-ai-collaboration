<template>
    <main class="vb-login-page">
        <section class="vb-login-card" aria-labelledby="login-title">
            <div class="vb-login-brand"><vb-icon name="bank" :size="28" /></div>
            <h1 id="login-title">视频银行坐席工作台</h1>
            <p>请输入坐席账号和密码</p>
            <form @submit.prevent="login">
                <label>坐席账号<input v-model.trim="username" autocomplete="username" inputmode="numeric" required autofocus></label>
                <label>密码<input v-model="password" type="password" autocomplete="current-password" required></label>
                <p v-if="error" class="vb-login-error" role="alert">{{ error }}</p>
                <button type="submit" :disabled="submitting">{{ submitting ? '正在登录…' : '登录' }}</button>
            </form>
        </section>
    </main>
</template>

<script setup>
import { ref } from 'vue';
import VbIcon from '@/ui/VbIcon.vue';
import { request } from '@/core/sdk.js';
import { setAgentIdentity, markAgentAuthenticated } from '@/core/identity.js';

const emit = defineEmits(['authenticated']);
const username = ref('');
const password = ref('');
const error = ref('');
const submitting = ref(false);

async function login() {
    error.value = '';
    submitting.value = true;
    try {
        const profile = await request('/api/platform/agent-login', { username: username.value, password: password.value });
        setAgentIdentity({ ...profile, agentName: profile.name, agentSkills: profile.skills || [] });
        markAgentAuthenticated();
        password.value = '';
        emit('authenticated');
    } catch (cause) {
        error.value = cause.message || '登录失败，请重试。';
    } finally {
        submitting.value = false;
    }
}
</script>
