/*
 * 告知书同意流程（源：mobile_balance.html acceptConsent，契约 §3.6 红线）。
 * 红线：用户点击「同意并开始服务」之前，任何摄像头/麦克风都不会被请求；
 * 该点击同时解锁 AudioContext、speechSynthesis、语音识别与等待音乐。
 */
import { logAction } from '../core/sdk.js';

export function createConsent(refs, options = {}) {
    const speech = options.speech;
    const waitTone = options.waitTone;
    const media = options.media;
    const onAccepted = options.onAccepted || (() => {});
    let consented = false;
    let wired = false;
    let disposed = false;

    function acceptConsent() {
        if (consented || disposed) return;
        consented = true;
        waitTone?.prime();                                  // 点击手势内解锁音频（红线）
        refs.consent.style.display = 'none';
        speech?.unlock();
        const continuingRequest = onAccepted() === true;    // 同意之后才允许取本地流
        speech?.chime();
        logAction('CONSENT_AGREED', '用户同意远程银行告知书', {}, '').catch(() => {});
        // 同意本身不申请媒体。仅当 Flowable 推进至人工视频节点后，由
        // video-session.resume() 在该节点的接入链路中申请摄像头/麦克风。
        if (!continuingRequest) {
            setTimeout(() => {
                speech?.speak(refs.welcome?.textContent || '您好！我是您的专属金融数字顾问晨曦。');
            }, 300);
        }
    }

    function show() {
        if (consented || disposed) return;
        refs.consent.style.display = 'flex';
    }

    function wire() {
        if (wired) return;
        wired = true;
        refs.consentBtn?.addEventListener('click', acceptConsent);
    }

    wire();

    return {
        accept: acceptConsent,
        show,
        wire,
        get consented() { return consented; },
        dispose() {
            disposed = true;
            refs.consentBtn?.removeEventListener('click', acceptConsent);
        },
    };
}

export default createConsent;
