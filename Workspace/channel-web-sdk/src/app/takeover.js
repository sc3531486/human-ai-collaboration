/*
 * 接管模式渲染（源：mobile_balance.html applyTakeoverMode）。
 * 只读服务端下发的 takeover_mode，业务不主动切换（契约 §6.3：渲染层，不联动视频决策）。
 * 视频画面本身由 video-widget 承担（§2.9）：模式变化时通知控件 dock 打开/收起；
 * 控件未接入时仅做舞台图层与文案渲染，业务照常办理。
 */
export function createTakeover(refs, options = {}) {
    const speech = options.speech;
    const engage = options.engageWidget || (async () => false);
    const release = options.releaseWidget || (() => {});
    const notice = options.onNotice || (() => {});
    let mode = 'ai'; // 'ai' | 'av' | 'audio' | 'released'

    const showPip = (show) => {
        // 控件未接入时由本地兜底预览承担画中画；接入后控件自渲染，这里保持隐藏。
        if (refs.widgetActive) return;
        if (refs.pip) refs.pip.style.display = show ? 'block' : 'none';
    };

    async function apply(next) {
        if (!next || mode === next) return;
        mode = next;

        if (next === 'av') {
            if (window.speechSynthesis) window.speechSynthesis.cancel();
            if (refs.avatar) { refs.avatar.style.display = 'none'; refs.avatar.style.zIndex = '10'; }
            showPip(true);
            if (refs.text) refs.text.textContent = '🎥 远程座席 9527 (面对面见证中)';
            if (refs.dot) refs.dot.style.background = '#dc2626';
            notice('🔔 [房间通知] 9527号座席已接入主舞台，正在进行面对面视频见证...');
            await engage({ mode: 'dock', agent: true });
        } else if (next === 'audio') {
            if (refs.avatar) { refs.avatar.style.display = 'flex'; refs.avatar.style.zIndex = '10'; }
            showPip(true);
            if (refs.text) refs.text.textContent = '🎙️ 远程座席 9527 (语音接管中 · 数字人同屏)';
            if (refs.dot) refs.dot.style.background = '#f59e0b';
            if (refs.dhTitle) refs.dhTitle.textContent = '远程座席已语音介入 (数字人同屏见证)';
            if (refs.dhBadge) refs.dhBadge.textContent = '人机协同见证';
            notice('🔔 [房间通知] 座席已接管语音通道，保持数字人画面同屏沟通...');
            await engage({ mode: 'dock', agent: true, audioOnly: true });
        } else if (next === 'released') {
            release();
            showPip(false);
            if (refs.avatar) { refs.avatar.style.display = 'flex'; refs.avatar.style.zIndex = '10'; }
            if (refs.text) refs.text.textContent = '🤖 智能数字员工 · 晨曦';
            if (refs.dot) refs.dot.style.background = '#2563eb';
            if (refs.dhTitle) refs.dhTitle.textContent = '您好，我是晨曦';
            if (refs.dhBadge) refs.dhBadge.textContent = '您的数字人客户经理';
        }
        // 'ai'：数字人主导，无需改渲染（默认态）。
        if (speech) speech.setAvatarState('idle');
    }

    return {
        apply,
        get mode() { return mode; },
    };
}

export default createTakeover;
