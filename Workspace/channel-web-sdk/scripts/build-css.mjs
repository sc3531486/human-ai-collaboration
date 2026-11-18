/*
 * 把 navis-web 的手机端基线样式机械转换为 SDK 可用的隔离样式。
 *
 * 转换规则（渠道方集成要求：不得污染宿主页面）：
 *   1. 丢弃 html / body / :root 全局规则：SDK 只拥有一块容器，不拥有页面。
 *   2. :root 变量改挂到 .vb-root，作为 SDK 根容器作用域。
 *   3. 所有 class 选择器加 `vb-` 前缀、所有 id 选择器降级为 `vb-` class（消除 id 特异性）。
 *   4. 每条选择器前置 `.vb-root ` 作用域，禁止任何全局选择器。
 *   5. keyframes 与 animation 引用同步加 `vb-` 前缀，避免与宿主动画撞名。
 *
 * 输出：src/styles/vb-banking.css（已提交，重复运行幂等）。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const project = resolve(here, '..');
const baseline = resolve(project, '..', 'navis-web');

const STYLE_ORDER = ['inline:mobile_balance.html', 'css:mobile_bank.css', 'css:mobile_nodes.css', 'css:password_mobile.css'];

function readSource(spec) {
    const [kind, name] = spec.split(':');
    if (kind === 'css') return readFileSync(join(baseline, 'public', 'css', name), 'utf8');
    const html = readFileSync(join(baseline, name), 'utf8');
    const start = html.indexOf('<style>');
    const end = html.indexOf('</style>', start);
    if (start < 0 || end < 0) throw new Error(`未找到 ${name} 的内联 <style> 块`);
    return html.slice(start + '<style>'.length, end);
}

const GLOBAL_ONLY = /^(html|body)$/;
/** @media / @supports 等条件块：整体保留，内部规则递归加作用域。 */
const AT_RULE = /^@(media|supports|document|layer|scope|container)\b/;

function splitTopLevel(selector) {
    const parts = [];
    let depth = 0;
    let current = '';
    let inString = null;
    for (const ch of selector) {
        if (inString) {
            current += ch;
            if (ch === inString) inString = null;
            continue;
        }
        if (ch === '"' || ch === "'") { inString = ch; current += ch; continue; }
        if (ch === '(' || ch === '[') depth++;
        else if (ch === ')' || ch === ']') depth--;
        if (ch === ',' && depth === 0) {
            parts.push(current);
            current = '';
        } else current += ch;
    }
    parts.push(current);
    return parts.map(part => part.trim()).filter(Boolean);
}

const ID_MARK = '\u0000id\u0000';

/**
 * id 选择器降级为 class（消除 id 特异性带来的宿主冲突），class 统一加 vb- 前缀。
 * 两步都用占位符：否则 id 转出的 .vb-x 会被 class 规则再套一层 vb-，变成 .vb-vb-x。
 */
function prefixPart(part) {
    return part
        .replace(/#([A-Za-z][\w-]*)/g, `.${ID_MARK}$1`)
        .replace(/\.([A-Za-z_][\w-]*)/g, `.vb-$1`)
        .replace(new RegExp(`\\.${ID_MARK}`, 'g'), '.vb-')
        .replace(/:root/g, '.vb-root');
}

function handleSelector(selector) {
    const raw = selector.trim();
    if (!raw) return '';
    if (raw.startsWith('@')) {
        if (/^@(-webkit-)?keyframes/.test(raw)) return raw.replace(/^@(-webkit-)?keyframes\s+([\w-]+)/, (_m, p, name) => `@${p || ''}keyframes vb-${name}`);
        return raw;
    }
    const kept = [];
    for (const part of splitTopLevel(raw)) {
        if (GLOBAL_ONLY.test(part)) continue;                 // 宿主页面的 html/body 不由 SDK 重置
        if (part === '*' || part.startsWith('*:')) { kept.push('.vb-root', `.vb-root ${part.slice(1) || ''}`.trim()); continue; }
        const prefixed = prefixPart(part);
        kept.push(prefixed.startsWith('.vb-root') ? prefixed : `.vb-root ${prefixed}`);
    }
    const cleaned = kept.filter(Boolean).join(', ');
    return cleaned ? `${cleaned} ` : '';
}

function transform(css, inMedia = false) {
    let out = '';
    let buffer = '';
    let i = 0;
    while (i < css.length) {
        const ch = css[i];
        if (ch === '{') {
            const raw = stripComments(buffer);
            buffer = '';
            let depth = 1;
            let j = i + 1;
            while (j < css.length) {
                if (css[j] === '{') depth++;
                else if (css[j] === '}') { depth--; if (depth === 0) break; }
                j++;
            }
            const body = css.slice(i + 1, j);
            const trimmed = raw.trim();
            const isKeyframe = /^@(-webkit-)?keyframes/.test(trimmed);
            const isAtRule = AT_RULE.test(trimmed);
            const head = isAtRule ? `${trimmed} ` : handleSelector(trimmed);
            if (head) out += `${head}{${isKeyframe ? body : transform(body, inMedia || isAtRule)}}`;
            i = j + 1;
        } else {
            buffer += ch;
            i++;
        }
    }
    return out + stripComments(buffer);
}

/** 去掉注释：注释里出现 "html" 之类字样不该被当成选择器。 */
function stripComments(text) {
    return text.replace(/\/\*[\s\S]*?\*\//g, '');
}

function collectKeyframes(css) {
    return [...css.matchAll(/@(-webkit-)?keyframes\s+([\w-]+)/g)].map(match => match[2]);
}

const sources = STYLE_ORDER.map(spec => `/* 源：navis-web ${spec.replace(':', '/')} */\n${readSource(spec)}`);
const merged = sources.join('\n\n');
const names = [...new Set(collectKeyframes(merged))];
let output = transform(merged);
for (const name of names) output = output.replace(new RegExp(`(?<![\\w-])${name}(?![\\w-])`, 'g'), `vb-${name}`);

const header = `/*
 * 由 scripts/build-css.mjs 从 navis-web 基线机械生成，请勿手工编辑。
 * 全部选择器带 vb- 前缀并收在 .vb-root 作用域内 —— SDK 不拥有宿主页面。
 */\n`;
writeFileSync(join(project, 'src', 'styles', 'vb-banking.css'), header + output, 'utf8');
console.log(`[css] 已生成 src/styles/vb-banking.css（${output.length} 字节，keyframes: ${names.join(', ') || '无'}）`);
