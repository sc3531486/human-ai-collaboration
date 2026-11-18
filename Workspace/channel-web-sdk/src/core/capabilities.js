/*
 * 运行环境能力探测（契约 §8.2）。
 * 全部为软依赖：缺能力走降级路径，不静默失败——缺失项显式列给调用方。
 */

const CRITICAL = [
    { key: 'fetch', label: 'fetch 网络请求', test: () => typeof fetch === 'function' },
    { key: 'WebSocket', label: 'WebSocket 实时通道', test: () => typeof WebSocket === 'function' },
    { key: 'Promise', label: 'Promise 异步', test: () => typeof Promise === 'function' },
];

const OPTIONAL = [
    { key: 'getUserMedia', label: '摄像头/麦克风（可用宿主桥或模拟流降级）', test: () => Boolean(navigator?.mediaDevices?.getUserMedia) },
    { key: 'speechSynthesis', label: '语音播报（缺省仅文字展示）', test: () => typeof window !== 'undefined' && 'speechSynthesis' in window },
    { key: 'WebAssembly', label: 'WebAssembly（编解码加速，缺省回退 bridge 质量档）', test: () => typeof WebAssembly === 'object' },
    { key: 'localStorage', label: '本地存储（缺省留痕不持久化）', test: () => { try { localStorage.setItem('vb.probe', '1'); localStorage.removeItem('vb.probe'); return true; } catch { return false; } } },
];

export function probeCapabilities() {
    const missingCritical = CRITICAL.filter((item) => {
        try {
            return !item.test();
        } catch {
            return true;
        }
    }).map((item) => item.label);
    const missingOptional = OPTIONAL.filter((item) => {
        try {
            return !item.test();
        } catch {
            return true;
        }
    }).map((item) => item.label);
    return {
        ok: missingCritical.length === 0,
        missingCritical,
        missingOptional,
        /** 缺关键能力时 startBusiness 抛出的人话错误。 */
        criticalMessage: missingCritical.length
            ? `当前浏览器环境不支持：${missingCritical.join('、')}。请使用较新版本的浏览器打开。`
            : '',
    };
}

export default { probeCapabilities };
