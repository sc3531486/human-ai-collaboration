/*
 * 数字人个性化配置（源：mobile_balance.html loadDigitalHumanConfig）。
 * 姓名 / 徽标 / 欢迎语 / 快捷问法 / 模型一律由 /api/balance/ai/config 下发，零硬编码（契约 §3.6）。
 * 失败不阻断挂载：catch + warn，保持静态兜底文案。
 */
import { aiConfig } from '../core/sdk.js';
import { chipButton } from './shell.js';
import { registry } from '../core/registry.js';

export async function loadAiConfig(refs, options = {}) {
    try {
        const data = await aiConfig();
        if (!data?.success || !data.data) return null;
        const profile = data.data.profile || {};
        const rules = data.data.rules || {};
        const llm = data.data.llm || {};
        const result = { model: '', greeting: '', quickQuestions: null };

        if (llm.model) result.model = llm.model;
        if (profile.title && refs.dhTitle) refs.dhTitle.textContent = `您好，我是${profile.name || '晨曦'}`;
        if ((profile.title || profile.name) && refs.text) refs.text.textContent = `${profile.name || '晨曦'} · 在线为您服务`;
        if ((profile.roleTag || result.model) && refs.dhBadge) {
            refs.dhBadge.textContent = `${profile.roleTag || 'AI 专属顾问'} · ${result.model}`;
            refs.dhBadge.title = `AI 驱动模型：${result.model}`;
        }
        // 渠道显式提供的资源优先；未配置时才允许服务端档案覆盖 SDK 内置兜底图。
        if (profile.avatarUrl && refs.img && !refs.avatarConfigured) refs.img.src = profile.avatarUrl;
        if (profile.greeting && refs.welcome && !options.chatHome) {
            refs.welcome.textContent = profile.greeting;
            result.greeting = profile.greeting;
        }

        // 快捷问法：服务端规则优先，缺省回落到各场景 chips（registry 汇总）。
        const questions = (rules.quickQuestions && rules.quickQuestions.length)
            ? rules.quickQuestions
            : registry.chips();
        if (questions.length && refs.chips && !options.chatHome) {
            refs.chips.innerHTML = '';
            questions.forEach((question) => {
                const button = chipButton(question);
                button.onclick = () => options.onQuick?.(question);
                refs.chips.appendChild(button);
            });
            result.quickQuestions = questions;
        }
        return result;
    } catch (error) {
        console.warn('加载数字人动态配置提示:', error);
        return null;
    }
}

export default loadAiConfig;
