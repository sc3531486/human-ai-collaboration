/* 将独立视频控件的已发布制品复制到坐席端 public 目录。
 * 坐席端只按 /video-widget/ UMD 契约加载，绝不引入控件源码或参与其打包。 */
import { copyFileSync, existsSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const appRoot = resolve(here, '..');
const workspace = resolve(appRoot, '..');
const source = join(workspace, 'video-widget', 'dist');
const target = join(appRoot, 'public', 'video-widget');

function copyTree(from, to) {
    let count = 0;
    for (const name of readdirSync(from)) {
        const fromFile = join(from, name);
        const toFile = join(to, name);
        if (statSync(fromFile).isDirectory()) {
            mkdirSync(toFile, { recursive: true });
            count += copyTree(fromFile, toFile);
        } else {
            mkdirSync(dirname(toFile), { recursive: true });
            copyFileSync(fromFile, toFile);
            count += 1;
        }
    }
    return count;
}

if (!existsSync(source)) {
    console.warn('[copy-video-widget] video-widget/dist 不存在；请先执行 video-widget 的 npm run build。');
    process.exit(0);
}
mkdirSync(target, { recursive: true });
console.log(`[copy-video-widget] 已复制 ${copyTree(source, target)} 个文件 → public/video-widget/`);
