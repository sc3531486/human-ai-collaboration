/*
 * 人工排队等待音乐（源：public/js/mobile-wait-tone.js，逐行移植）。
 * 等待判据（契约 §3.6 红线）收敛在 core/flow/policy.js —— 与视频接入识别同一事实源。
 * 用户任意操作时 prime() 解锁音频；坐席音画到达或授权结束立即停止。
 */
import { isWaitingForAgent } from '../core/flow/policy.js';
export function createWaitTone(refs) {
    let context = null;
    let wanted = false;
    let timer = null;
    const voices = new Set();

    const prompt = (show) => {
        const button = refs.waitResume;
        if (button) {
            button.hidden = !show;
            button.textContent = '点击播放等待音乐';
        }
    };

    function note(frequency, start, duration, volume) {
        const oscillator = context.createOscillator();
        const gain = context.createGain();
        oscillator.type = 'sine';
        oscillator.frequency.value = frequency;
        gain.gain.setValueAtTime(0, start);
        gain.gain.linearRampToValueAtTime(volume, start + 0.06);
        gain.gain.setValueAtTime(volume, start + duration - 0.12);
        gain.gain.linearRampToValueAtTime(0, start + duration);
        oscillator.connect(gain);
        gain.connect(context.destination);
        const voice = { oscillator, gain };
        voices.add(voice);
        oscillator.onended = () => {
            voices.delete(voice);
            oscillator.disconnect();
            gain.disconnect();
        };
        oscillator.start(start);
        oscillator.stop(start + duration + 0.02);
    }

    function tick() {
        if (!wanted || context?.state !== 'running') return;
        [261.63, 329.63, 392, 523.25, 392, 329.63, 293.66, 261.63]
            .forEach((frequency, index) => note(frequency, context.currentTime + index * 0.6, 0.5, 0.075));
    }

    function play() {
        if (!wanted) return;
        if (context?.state !== 'running') {
            prompt(true);
            return;
        }
        prompt(false);
        if (timer !== null) return;
        tick();
        timer = setInterval(tick, 6000);
    }

    async function prime() {
        const Audio = window.AudioContext || window.webkitAudioContext;
        if (!Audio) {
            if (wanted) prompt(true);
            return;
        }
        try {
            if (!context) context = new Audio();
            // 在点击处理器中真正启动一次无声声源，避免移动浏览器之后仍阻止音频。
            note(440, context.currentTime, 0.2, 0);
            if (context.state !== 'running') await context.resume();
            play();
        } catch {
            if (wanted) prompt(true);
        }
    }

    function stop() {
        wanted = false;
        if (timer !== null) clearInterval(timer);
        timer = null;
        prompt(false);
        if (refs.waitPanel) refs.waitPanel.hidden = true;
        for (const voice of voices) {
            try { voice.oscillator.stop(); } catch { /* 已停止 */ }
            voice.oscillator.disconnect();
            voice.gain.disconnect();
        }
        voices.clear();
    }

    function sync(state, connected) {
        if (!isWaitingForAgent(state, connected)) {
            stop();
            return;
        }
        wanted = true;
        if (refs.waitPanel) refs.waitPanel.hidden = false;
        if (context && context.state !== 'running') {
            context.resume().then(play).catch(() => prompt(true));
        } else {
            play();
        }
    }

    return { prime, sync, stop };
}

export default createWaitTone;
