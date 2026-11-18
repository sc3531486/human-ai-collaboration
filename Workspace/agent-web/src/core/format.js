/* 展示格式化：口径与 agent_common.js / agent_workspace.js 一致，不含任何场景语义。 */

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** 所有用户可控字符串渲染前转义。 */
export function escape(value) {
    return String(value == null ? '—' : value).replace(/[&<>"']/g, char => ESCAPES[char]);
}

const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' });
export function money(value) {
    return value == null || value === '' ? '—' : currency.format(Number(value));
}

export const pad2 = value => String(value).padStart(2, '0');

/** 时间统一按 HH:MM:SS 定宽呈现，避免左栏数字随秒数跳动。 */
export function hms(seconds) {
    const total = Math.max(0, Math.floor(Number(seconds) || 0));
    return `${pad2(Math.floor(total / 3600))}:${pad2(Math.floor(total / 60) % 60)}:${pad2(total % 60)}`;
}

/**
 * 后端时间列序列化为 ISO（2026-09-15T13:29:37），表格里统一转成「2026-09-15 13:29:37」；
 * 已经是空格分隔或无法识别的值原样透出，不猜格式。
 */
export function stamp(value) {
    return String(value == null ? '' : value).replace(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2}).*$/, '$1 $2');
}

/** 公告时间按 月-日 时:分 呈现，无法识别的格式原样透出。 */
export function noticeTime(value) {
    return String(value || '').replace(/^\d{4}-(\d{2}-\d{2})[ T](\d{2}:\d{2}).*$/, '$1 $2');
}

// 证件类型：后端存证件代码，展示时补成「代码-名称」；旧数据里的中文名称与未登记取值原样透出，不猜测。
const CERT_TYPES = {
    '01': '第二代居民身份证',
    '02': '港澳居民来往内地通行证',
    '03': '台湾居民来往大陆通行证',
    '04': '外国护照'
};
export function certLabel(value) {
    if (!value) return '—';
    const name = CERT_TYPES[String(value)];
    return name ? `${value}-${name}` : String(value);
}

/**
 * 影像 URL 校验：必须命中 `/records/<安全字符>` 才可渲染或下载，防止任意 URL 注入。
 * 这是全站唯一允许放行的影像地址形态。
 */
export function safeRecordUrl(url) {
    return typeof url === 'string' && /^\/records\/[A-Za-z0-9_.-]+$/.test(url) ? url : '';
}

/** PDF 回执单独校验扩展名，其余影像类型不得当作文档链接。 */
export function safeDocumentUrl(url) {
    return typeof url === 'string' && /^\/records\/[A-Za-z0-9_.-]+\.pdf$/.test(url) ? url : '';
}

/** 影像 URL 是否安全可用（渲染前的统一入口）。 */
export const isSafeRecord = url => Boolean(safeRecordUrl(url));
