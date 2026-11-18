/*
 * 数字人语音与态势：清脆叮咚提示音、TTS 朗读、舞台状态灯、语音输入（源：mobile_balance.html）。
 * 红线（契约 §3.6）：AudioContext 与 speechSynthesis 只在用户手势里解锁——由 consent.js / prime() 触发。
 */
export function createSpeech(refs, options = {}) {
    const getMode = options.getMode || (() => 'ai');
    let audioCtx = null;
    let cachedVoices = [];
    let lastText = '';
    let recognition = null;
    let listening = false;
    let disposed = false;

    if (typeof window !== 'undefined' && window.speechSynthesis) {
        cachedVoices = window.speechSynthesis.getVoices();
        if (window.speechSynthesis.onvoiceschanged !== undefined) {
            window.speechSynthesis.onvoiceschanged = () => {
                cachedVoices = window.speechSynthesis.getVoices();
            };
        }
    }

    function context() {
        const AudioCtx = window.AudioContext || window.webkitAudioContext;
        if (!AudioCtx) return null;
        if (!audioCtx) {
            try {
                audioCtx = new AudioCtx();
            } catch {
                return null;
            }
        }
        if (audioCtx.state === 'suspended') audioCtx.resume().catch(() => {});
        return audioCtx;
    }

    /** 双音清脆叮咚声 (C5 → E5)。 */
    function chime() {
        if (disposed) return;
        try {
            const ctx = context();
            if (!ctx) return;
            const now = ctx.currentTime;
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = 'sine';
            osc.frequency.setValueAtTime(523.25, now);
            osc.frequency.exponentialRampToValueAtTime(659.25, now + 0.12);
            gain.gain.setValueAtTime(0.08, now);
            gain.gain.exponentialRampToValueAtTime(0.001, now + 0.35);
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.start(now);
            osc.stop(now + 0.35);
        } catch {
            /* 提示音失败不影响业务 */
        }
    }

    /** 状态灯与舞台文案（模式来自服务端 takeover_mode，只做渲染）。 */
    function setAvatarState(state) {
        const avatar = refs.avatar;
        if (avatar) avatar.classList.remove('vb-listening', 'vb-thinking', 'vb-speaking');
        if (disposed) return;
        if (refs.img && refs.speakingAvatarUrl) {
            const src = state === 'speaking' && getMode() !== 'av' ? refs.speakingAvatarUrl : refs.defaultAvatarUrl;
            if (refs.img.getAttribute('src') !== src) refs.img.setAttribute('src', src);
        }

        if (state === 'speaking') {
            if (getMode() !== 'av') {
                if (avatar) avatar.classList.add('vb-speaking');
                if (refs.text) refs.text.textContent = '🗣️ 智能数字员工正在播报...';
                if (refs.dot) refs.dot.style.background = '#3b82f6';
            }
        } else if (state === 'listening') {
            if (avatar) avatar.classList.add('vb-listening');
            if (refs.text) refs.text.textContent = '👂 正在聆听您的指令...';
            if (refs.dot) refs.dot.style.background = '#f59e0b';
        } else if (state === 'thinking') {
            if (avatar) avatar.classList.add('vb-thinking');
            if (refs.text) refs.text.textContent = '⚡ 数字人思考与解析中...';
            if (refs.dot) refs.dot.style.background = '#8b5cf6';
        } else {
            const mode = getMode();
            if (mode === 'ai' || mode === 'released') {
                const defaultName = refs.dhTitle ? refs.dhTitle.textContent : '您好，我是晨曦';
                if (refs.text) refs.text.textContent = `🤖 ${defaultName}`;
            }
            if (refs.dot) refs.dot.style.background = '#2563eb';
        }
    }

    function speak(text) {
        if (!text || disposed) return;
        lastText = text;
        chime();
        if (!window.speechSynthesis) return;

        const clean = String(text)
            .replace(/<[^>]+>/g, '')
            .replace(/[🤖⚡✅❌📝📲💳🛡️🎥🎙️🔔⚠️🏁]/g, '')
            .replace(/&nbsp;/g, ' ')
            .trim();
        if (!clean) return;

        try {
            window.speechSynthesis.cancel();
            setTimeout(() => {
                if (disposed) return;
                const utter = new SpeechSynthesisUtterance(clean);
                utter.lang = 'zh-CN';
                utter.rate = 1.05;
                utter.pitch = 1.08;
                const voices = cachedVoices.length > 0 ? cachedVoices : window.speechSynthesis.getVoices();
                let zhVoice = voices.find((v) => (v.lang === 'zh-CN' || v.lang === 'zh_CN')
                    && ['Xiaoxiao', 'Tingting', 'Huihui', 'Female', '普通话'].some((name) => v.name.includes(name)));
                if (!zhVoice) zhVoice = voices.find((v) => (v.lang && v.lang.toLowerCase().startsWith('zh')) || v.name.includes('Chinese'));
                if (zhVoice) utter.voice = zhVoice;
                utter.onstart = () => { if (getMode() !== 'av') setAvatarState('speaking'); };
                utter.onend = () => setAvatarState('idle');
                utter.onerror = () => setAvatarState('idle');
                window.speechSynthesis.resume();
                window.speechSynthesis.speak(utter);
            }, 60);
        } catch (error) {
            console.warn('TTS 朗读异常:', error);
        }
    }

    function replayLast() {
        if (lastText) {
            speak(lastText);
        } else {
            speak('您好！我是您的专属金融数字助手晨曦。请直接告诉我您需要办理什么业务，例如：我要给王总转账200万。');
        }
    }

    /** 用户手势瞬间解锁语音引擎（同意告知书时调用）。 */
    function unlock() {
        try {
            const dummy = new SpeechSynthesisUtterance('您好');
            dummy.volume = 0.01;
            window.speechSynthesis.speak(dummy);
        } catch {
            /* 浏览器不支持则仅靠文字 */
        }
    }

    function stopVoiceInput() {
        listening = false;
        refs.voiceBtn?.classList.remove('vb-active');
        if (refs.input) refs.input.placeholder = '请输入指令，或点击麦克风语音交流...';
        setAvatarState('idle');
    }

    /** Web Speech Recognition 语音输入：不支持时给出人话提示。 */
    function toggleVoiceInput(onText) {
        const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (!recognition) {
            if (!SpeechRecognition) {
                alert('当前浏览器环境暂未开启原生语音识别，请直接在输入框打字或点击推荐快捷问题。');
                return;
            }
            try {
                recognition = new SpeechRecognition();
                recognition.lang = 'zh-CN';
                recognition.continuous = false;
                recognition.interimResults = false;
                recognition.onstart = () => {
                    listening = true;
                    setAvatarState('listening');
                    refs.voiceBtn?.classList.add('vb-active');
                    if (refs.input) refs.input.placeholder = '正在聆听您的语音，请说话...';
                };
                recognition.onresult = (event) => {
                    const text = event.results?.[0]?.[0]?.transcript;
                    if (text) onText(text);
                };
                recognition.onerror = () => stopVoiceInput();
                recognition.onend = () => stopVoiceInput();
            } catch {
                recognition = null;
            }
        }
        if (!recognition) {
            alert('当前浏览器环境暂未开启原生语音识别，请直接在输入框打字或点击推荐快捷问题。');
            return;
        }
        if (listening) {
            try { recognition.stop(); } catch { /* 忽略 */ }
            stopVoiceInput();
        } else {
            try { recognition.start(); } catch (error) { console.warn(error); }
        }
    }

    function dispose() {
        disposed = true;
        try { recognition?.abort?.(); } catch { /* 忽略 */ }
        try { window.speechSynthesis?.cancel(); } catch { /* 忽略 */ }
        if (audioCtx) {
            try { audioCtx.close(); } catch { /* 忽略 */ }
            audioCtx = null;
        }
    }

    return {
        chime,
        speak,
        replayLast,
        unlock,
        setAvatarState,
        toggleVoiceInput,
        dispose,
        get lastText() { return lastText; },
    };
}

export default createSpeech;
