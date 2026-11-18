/*
 * 本地 SVG 图标：沿用 navis-web bank-icons.js 的 path 表，不引第三方图标库。
 * 用 DOM 构建输出，不拼接用户字符串（银行 WebView CSP 禁 unsafe-eval，且避免注入面）。
 */
import { svg } from './dom.js';

const PATHS = {
    bank: '<path d="m3 9 9-6 9 6M4 10h16M5 20h14M3 22h18M6 10v10m6-10v10m6-10v10"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
    headset: '<path d="M4 14v-2a8 8 0 0 1 16 0v2M20 17v2a2 2 0 0 1-2 2h-5"/><rect x="3" y="11" width="4" height="7" rx="2"/><rect x="17" y="11" width="4" height="7" rx="2"/>',
    arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
    transfer: '<path d="M4 7h16m-4-4 4 4-4 4M20 17H4m4-4-4 4 4 4"/>',
    shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z"/><path d="m8 12 3 3 5-6"/>',
    video: '<rect x="3" y="6" width="12" height="12" rx="3"/><path d="m15 10 6-3v10l-6-3z"/>',
    videoOff: '<path d="m3 3 18 18M15 10l6-3v10l-3-2M6 6H5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2v-1"/>',
    mic: '<rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-3 0h6"/>',
    micOff: '<path d="m3 3 18 18M9 9v3a3 3 0 0 0 4 3M9 5a3 3 0 0 1 6 0v5M5 10v2a7 7 0 0 0 12 5M19 10v2M12 19v3m-3 0h6"/>',
    phone: '<path d="M7 3H4a1 1 0 0 0-1 1c0 10 7 17 17 17a1 1 0 0 0 1-1v-3l-5-2-2 2a14 14 0 0 1-7-7l2-2z"/>',
    phoneOff: '<path d="M3 14c4-6 14-6 18 0l-3 4-4-3v-3m-4 0v3l-4 3z"/>',
    file: '<path d="M14 3H5v18h14V8zM14 3v6h5M8 13h8m-8 4h5"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/>',
    bell: '<path d="M6 8a6 6 0 0 1 12 0c0 6 3 6 3 8H3c0-2 3-2 3-8m3 11a3 3 0 0 0 6 0"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5M5 7a8 8 0 0 1 13-3l2 3M4 17l2 3a8 8 0 0 0 13-3"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
    users: '<path d="M16 21v-2a6 6 0 0 0-12 0v2m16 0v-2a5 5 0 0 0-3-5M15 3a4 4 0 0 1 0 8"/><circle cx="10" cy="7" r="4"/>',
    camera: '<path d="M4 7h3l2-3h6l2 3h3v14H4z"/><circle cx="12" cy="13" r="4"/>',
    chart: '<path d="M4 3v17h17M8 16v-4m5 4V6m5 10V9"/>',
    spark: '<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5 6.5L3 12l6.5-2.5z"/>',
    message: '<path d="M4 4h16v13H8l-4 4z"/><path d="M8 8h8m-8 5h5"/>',
    volume: '<path d="m11 4-6 5H2v6h3l6 5zM15 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14"/>',
    settings: '<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3"/><circle cx="15" cy="17" r="3"/>',
    logout: '<path d="M10 4H4v16h6m3-12 4 4-4 4m-5-4h13"/>',
    archive: '<rect x="3" y="3" width="18" height="5" rx="1"/><path d="M5 8v13h14V8m-9 5h4"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v1"/>',
    send: '<path d="m3 3 18 9-18 9 4-9zM7 12h14"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3"/>',
    screen: '<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/>',
    pause: '<rect x="7" y="5" width="4" height="14" rx="1"/><rect x="13" y="5" width="4" height="14" rx="1"/>',
    deck: '<rect x="3" y="3" width="18" height="12" rx="2"/><path d="M12 15v6m-5 0h10"/>',
    share: '<path d="M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7"/><path d="M12 3v13m-5-5 5-5 5 5"/>',
    expert: '<circle cx="9" cy="8" r="4"/><path d="M2 21v-2a7 7 0 0 1 12-4.9M19 14v6m-3-3h6"/>',
    book: '<path d="M4 4h7a3 3 0 0 1 3 3v13a2 2 0 0 0-2-2H4zM20 4h-3a3 3 0 0 0-3 3v13a2 2 0 0 1 2-2h4z"/>',
    calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4m8-4v4"/>',
    wallet: '<rect x="3" y="6" width="18" height="13" rx="2"/><path d="M3 10h18M16 14h2"/>',
    signin: '<path d="M14 4h6v16h-6m-3-4-4-4 4-4M3 12h11"/>',
};

/** 表情 → 图标名：对齐 navis-web mobile_polish.js 的映射表。 */
const EMOJI = {
    '🏦': 'bank', '🤖': 'spark', '💳': 'transfer', '💰': 'bank', '📋': 'file', '📈': 'chart',
    '🧑‍💼': 'headset', '👩‍💼': 'headset', '🛡️': 'shield', '🔊': 'volume', '🎙️': 'mic', '✅': 'check',
    '⚠️': 'info', '📞': 'phone', '📝': 'file', '📲': 'message', '🔄': 'refresh', '📚': 'file',
    '💡': 'spark', '⚡': 'spark', '🔴': 'video', '✏️': 'file', '🧾': 'file', '❌': 'close',
    '📱': 'phone', '🏁': 'check', '🎥': 'video', '🔔': 'bell', '🕐': 'clock',
};

export const ICON_PATHS = PATHS;
export const EMOJI_ICONS = EMOJI;

function markupFor(name, emoji) {
    const key = emoji ? EMOJI[emoji] || '' : name;
    return PATHS[key] || PATHS.file;
}

/** 构建一个 <svg> 图标元素。 */
export function icon(name, size = 20, emoji = '') {
    const markup = markupFor(name, emoji);
    const dimension = Number(size) || 20;
    return svg(markup, {
        width: dimension,
        height: dimension,
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: 'currentColor',
        'stroke-width': '1.7',
        'stroke-linecap': 'round',
        'stroke-linejoin': 'round',
        'aria-hidden': 'true',
        class: 'vb-icon',
    });
}

/** 静态 path 表片段（仅用于模板中的固定图标，用户输入不得进入此参数）。 */
export function iconHtml(name, size = 20, emoji = '') {
    const markup = markupFor(name, emoji);
    const dimension = Number(size) || 20;
    return `<svg class="vb-icon" width="${dimension}" height="${dimension}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${markup}</svg>`;
}

export default { icon, iconHtml, ICON_PATHS, EMOJI_ICONS };
