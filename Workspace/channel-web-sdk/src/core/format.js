/* 场景无关的格式与转义工具。所有用户可控字符串渲染前必须过 escape（契约 §3.7）。 */

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** HTML 转义：与 navis-web 的 BankApp.escape / mobileEscape 同一张映射表。 */
export const escape = (value) =>
    String(value ?? '').replace(/[&<>"']/g, (char) => ESCAPES[char]);

/** 影像地址白名单：只有 /records/ 下的单层文件名才可渲染或下载，防止任意 URL 注入。 */
export const RECORD_URL = /^\/records\/[A-Za-z0-9_.-]+$/;
export const RECORD_PDF_URL = /^\/records\/[A-Za-z0-9_.-]+\.pdf$/;

export const isRecordUrl = (value) => typeof value === 'string' && RECORD_URL.test(value);
export const isRecordPdfUrl = (value) => typeof value === 'string' && RECORD_PDF_URL.test(value);

/** 合法的影像地址原样返回，其余一律返回空串（不渲染、不出链接）。 */
export const safeRecordUrl = (value) => (isRecordUrl(value) ? String(value) : '');

export function money(value) {
    if (value === null || value === undefined || value === '') return '—';
    const amount = Number(value);
    if (!Number.isFinite(amount)) return escape(value);
    try {
        return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(amount);
    } catch {
        return `${amount.toFixed(2)} 元`;
    }
}

export const hms = (seconds) => {
    const total = Math.max(0, Math.round(Number(seconds) || 0));
    const pad = (part) => String(part).padStart(2, '0');
    return `${pad(Math.floor(total / 3600))}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`;
};

export function stamp(value) {
    const date = value instanceof Date ? value : new Date(String(value ?? ''));
    if (Number.isNaN(date.getTime())) return '—';
    return date.toLocaleString('zh-CN', { hour12: false });
}

const CERT_LABELS = {
    ID_CARD: '居民身份证',
    PASSPORT: '护照',
    HK_MO: '港澳居民来往内地通行证',
    TW: '台湾居民来往大陆通行证',
    OFFICER: '军官证',
};

/** 证件类型编码转中文名；未知编码原样转义返回，不臆造名称。 */
export const certLabel = (value) =>
    CERT_LABELS[String(value ?? '').trim().toUpperCase()] || escape(value);

/** 中间打码：卡号/证件号展示用。 */
export function mask(value, head = 4, tail = 4) {
    const text = String(value ?? '');
    if (text.length <= head + tail) return text;
    return `${text.slice(0, head)} **** ${text.slice(-tail)}`;
}

/** 把基线里混在文本中的 <br> 拆成多行，避免任何 innerHTML 大段拼接。 */
export const splitLines = (value) => String(value ?? '').split(/<br\s*\/?>/i);

/** 去掉表情与标签，只留可朗读文本（对齐 navis-web speakText 的清洗口径）。 */
export function speechText(value) {
    return String(value ?? '')
        .replace(/<[^>]+>/g, '')
        .replace(/&nbsp;/g, ' ')
        // 按码点区间手写，兼容旧 WebView 的 unicode 属性转义。
        .replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]|[\u2600-\u27BF]/g, '')
        .trim();
}
