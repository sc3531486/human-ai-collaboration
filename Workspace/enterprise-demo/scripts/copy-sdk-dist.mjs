/*
 * 把 channel-web-sdk/dist 拷贝到本项目 public/vendor/channel-web-sdk/，
 * 供运行时以 UMD script 标签或 ESM 动态 import 加载（契约 §0.1：不互相 import 源码、不建 workspace 包）。
 *
 * dist 尚未构建时（缺 vb-banking.umd.js / vb-banking.esm.js）本脚本只提示并正常退出，
 * 应用侧由 src/core/mock-sdk.js 桩件接管（契约 §6.1 接口对齐）。
 *
 * 注意：递归拷贝用逐文件 copyFileSync 而非 fs.cpSync——后者在部分 Windows 环境
 * 直接以 0xC0000409 崩溃（npm prebuild 会因此 exit 1）。
 */
import { copyFileSync, existsSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '..');
const workspaceRoot = resolve(projectRoot, '..');

const src = join(workspaceRoot, 'channel-web-sdk', 'dist');
const dest = join(projectRoot, 'public', 'vendor', 'channel-web-sdk');
const widgetSrc = join(workspaceRoot, 'video-widget', 'dist');
const widgetDest = join(projectRoot, 'public', 'vendor', 'video-widget');

function copyTree(from, to) {
    let count = 0;
    for (const entry of readdirSync(from)) {
        const source = join(from, entry);
        const target = join(to, entry);
        if (statSync(source).isDirectory()) {
            mkdirSync(target, { recursive: true });
            count += copyTree(source, target);
        } else {
            mkdirSync(dirname(target), { recursive: true });
            copyFileSync(source, target);
            count += 1;
        }
    }
    return count;
}

if (!existsSync(src)) {
    console.log('[copy-sdk-dist] channel-web-sdk/dist 不存在，跳过（应用将使用桩件 SDK）。');
    process.exit(0);
}

const entries = readdirSync(src);
const hasBundle = entries.includes('vb-banking.umd.js') || entries.includes('vb-banking.esm.js');
if (!hasBundle) {
    console.log('[copy-sdk-dist] dist 中尚无 vb-banking.umd.js / vb-banking.esm.js（仅有静态资源），仅拷贝静态资源。');
    console.log('[copy-sdk-dist] 应用将使用桩件 SDK；待 channel-web-sdk 构建就绪后重跑 npm run vendor:sdk 即可切换。');
}

try {
    mkdirSync(dest, { recursive: true });
    const count = copyTree(src, dest);
    console.log(`[copy-sdk-dist] 已拷贝 ${count} 个文件 → public/vendor/channel-web-sdk/`);
} catch (error) {
    console.warn('[copy-sdk-dist] 拷贝失败：', error.message);
    process.exit(0);
}

const copied = readdirSync(dest);
if (copied.includes('vb-banking.umd.js') || copied.includes('vb-banking.esm.js')) {
    console.log('[copy-sdk-dist] 检测到真实 SDK 产物，loader 将自动使用真实 SDK。');
} else {
    console.log('[copy-sdk-dist] 未检测到 SDK bundle，loader 将回退到桩件 SDK。');
}

if (!existsSync(widgetSrc)) {
    console.warn('[copy-sdk-dist] video-widget/dist 不存在，渠道演示端的视频控件不可用。');
} else {
    try {
        mkdirSync(widgetDest, { recursive: true });
        const count = copyTree(widgetSrc, widgetDest);
        console.log(`[copy-sdk-dist] 已拷贝 ${count} 个视频控件文件 → public/vendor/video-widget/`);
    } catch (error) {
        console.warn('[copy-sdk-dist] 视频控件拷贝失败：', error.message);
    }
}
