/*
 * SDK 运行时加载器（契约 §0.1 / §6.1）。
 *
 * 四交付物不互相 import 源码、不建 workspace 包：channel-web-sdk 的构建产物
 * 经 scripts/copy-sdk-dist.mjs 拷入 public/vendor/channel-web-sdk/，
 * 这里在运行时按序探测：
 *   1) ESM：动态 import 产物 URL，保留原生模块加载的运行时隔离。
 *   2) UMD：script 标签挂 window.VideoBanking（兼容传统企业 Web 接入）
 *   3) 都缺席 → 桩件 SDK（接口按 §6.1 mount(el, config) + bridge contract 对齐）
 *
 * 判产物存在不能只看 res.ok：SPA 回退会把缺失路径以 200 + text/html 返回，
 * 必须校验 content-type 是 javascript，否则会把 index.html 当脚本加载。
 */
import { loadMockSdk } from './mock-sdk.js';

const VENDOR_BASE = '/vendor/channel-web-sdk/';
// 版本参数让渠道页在 SDK 发布后不会继续命中浏览器中的旧 bundle。
const SDK_RELEASE = 'password-camera-v21';
const UMD_URL = VENDOR_BASE + 'vb-banking.umd.js?v=' + SDK_RELEASE;
const ESM_URL = VENDOR_BASE + 'vb-banking.esm.js?v=' + SDK_RELEASE;
const CSS_URL = VENDOR_BASE + 'vb-banking.css?v=' + SDK_RELEASE;

async function existsJs(url) {
    try {
        const res = await fetch(url, { method: 'GET', cache: 'no-store' });
        if (!res.ok) return false;
        const type = (res.headers.get('content-type') || '').toLowerCase();
        return type.includes('javascript') || type.includes('ecmascript');
    } catch {
        return false;
    }
}

function injectCss(url) {
    return new Promise((resolve) => {
        if (document.querySelector('link[data-vb-sdk]')) return resolve();
        const link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = url;
        link.setAttribute('data-vb-sdk', '');
        link.onload = () => resolve(true);
        link.onerror = () => resolve(false);
        document.head.appendChild(link);
    });
}

function loadScript(url) {
    return new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = url;
        script.setAttribute('data-vb-sdk', '');
        script.onload = () => resolve(true);
        script.onerror = () => {
            script.remove();
            reject(new Error('SDK UMD 产物加载失败：' + url));
        };
        document.head.appendChild(script);
    });
}

/** 从 UMD/ESM 命名空间里解析出 mount API。 */
function pickApi(candidate) {
    if (!candidate) return null;
    if (typeof candidate.mount === 'function') return candidate;
    if (candidate.default && typeof candidate.default.mount === 'function') return candidate.default;
    return null;
}

/**
 * SDK 是运行时投放到 public/vendor 的交付物，不能让 Vite 对其进行源码转换。
 * 通过 Function 创建浏览器原生 import，既保留 ESM 的 Custom Element 注册，
 * 又不会触发 Vite 的「public 文件不能从源码 import」保护。
 */
function importVendorModule(url) {
    return new Function('moduleUrl', 'return import(moduleUrl);')(url);
}

/**
 * @returns {Promise<{ api: { mount: Function }, mode: 'umd' | 'esm' | 'mock', version: string }>}
 */
export async function loadSdk() {
    // 1) ESM 优先，避免因传统 UMD 回退丢失最新的二维形象和交互能力。
    if (await existsJs(ESM_URL)) {
        try {
            const mod = await importVendorModule(ESM_URL);
            const api = pickApi(mod);
            if (api) {
                await injectCss(CSS_URL);
                return { api, mode: 'esm', version: api.version || 'channel-web-sdk(esm)' };
            }
        } catch (error) {
            console.warn('[sdk-loader] ESM 加载失败，转 UMD：', error.message);
        }
    }

    // 2) UMD 仅作为兼容性兜底。
    if (await existsJs(UMD_URL)) {
        try {
            await loadScript(UMD_URL);
            const api = pickApi(window.VideoBanking) || pickApi(window.VBBanking);
            if (api) {
                await injectCss(CSS_URL);
                return { api, mode: 'umd', version: api.version || 'channel-web-sdk(umd)' };
            }
        } catch (error) {
            console.warn('[sdk-loader] UMD 加载失败，转桩件：', error.message);
        }
    }

    // 3) 产物未就绪 → 桩件先行（契约 §6.1 接口对齐，channel-web-sdk/dist 就绪后重跑 vendor:sdk 即自动切换）。
    return { api: loadMockSdk(), mode: 'mock', version: 'channel-web-sdk 桩件' };
}
