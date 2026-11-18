/*
 * 场景注册自检（契约 §5 / 任务验收）：
 * 断言「在 scenarios/ 下新增一个目录即可完成注册，任何已有文件零改动」。
 *
 * 做法：
 *   1. 对仓库现状（排除 node_modules / dist / 临时目录）做 SHA-256 快照；
 *   2. 复制 src + 构建配置到 .check-tmp/；
 *   3. 在副本里新增一个 fixture 场景目录（唯一动作）；
 *   4. 用仓库自带的 vite 在副本上执行一次真实库构建；
 *   5. 断言构建产物包含 fixture 场景 id（证明 import.meta.glob 自动注册），
 *      且副本中除「新增目录」外没有任何文件的哈希发生变化（证明零改动）；
 *   6. 断言仓库本身未被本自检改动，且源码树零 TypeScript / Vue 残留。
 */
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TMP = join(ROOT, '.check-tmp');
const EXCLUDE = new Set(['node_modules', 'dist', '.check-tmp', 'install.log', '.git', 'target']);

let failures = 0;
const ok = (condition, label) => {
    if (condition) {
        console.log(`  ✓ ${label}`);
    } else {
        failures += 1;
        console.error(`  ✗ ${label}`);
    }
};

function copyDir(from, to) {
    mkdirSync(to, { recursive: true });
    for (const name of readdirSync(from)) {
        const source = join(from, name);
        const target = join(to, name);
        if (statSync(source).isDirectory()) copyDir(source, target);
        else copyFileSync(source, target);
    }
}

function walk(dir, base = dir, out = []) {
    for (const name of readdirSync(dir)) {
        if (EXCLUDE.has(name)) continue;
        const full = join(dir, name);
        if (statSync(full).isDirectory()) walk(full, base, out);
        else out.push(relative(base, full));
    }
    return out;
}

function hashTree(dir) {
    const map = new Map();
    for (const rel of walk(dir)) {
        const digest = createHash('sha256').update(readFileSync(join(dir, rel))).digest('hex');
        map.set(rel.split('\\').join('/'), digest);
    }
    return map;
}

function diff(before, after) {
    const changed = [];
    for (const [rel, digest] of before) {
        if (!after.has(rel)) changed.push(`删除 ${rel}`);
        else if (after.get(rel) !== digest) changed.push(`改动 ${rel}`);
    }
    return changed;
}

console.log('[check] 1/6 仓库快照');
const repoBefore = hashTree(ROOT);
ok(repoBefore.size > 0, `快照覆盖 ${repoBefore.size} 个文件`);

console.log('[check] 2/6 源码树约束（零 TS / 去 Vue / 固定 glob / UMD 名）');
const registrySource = readFileSync(join(ROOT, 'src/core/registry.js'), 'utf8');
ok(registrySource.includes("import.meta.glob('../scenarios/*/index.js', { eager: true })"),
    'registry.js 使用契约规定的 import.meta.glob 精确写法');
const legacy = walk(join(ROOT, 'src')).filter((rel) => /\.(ts|vue|scss)$/.test(rel));
ok(legacy.length === 0, `src/ 无 TypeScript / Vue / SCSS 残留${legacy.length ? `（发现 ${legacy.join(', ')}）` : ''}`);
const viteConfig = readFileSync(join(ROOT, 'vite.config.js'), 'utf8');
ok(viteConfig.includes("name: 'VideoBanking'"), 'UMD 全局名为 window.VideoBanking（契约 §8.2）');
ok(viteConfig.includes("'es2018'"), '构建目标为 ES2018+');
ok(!existsSync(join(ROOT, 'tsconfig.json')) && !existsSync(join(ROOT, 'vite.config.ts')),
    '无 tsconfig.json / vite.config.ts');

console.log('[check] 3/6 复制工作副本并新增 fixture 场景');
rmSync(TMP, { recursive: true, force: true });
mkdirSync(TMP, { recursive: true });
copyDir(join(ROOT, 'src'), join(TMP, 'src'));
copyFileSync(join(ROOT, 'index.html'), join(TMP, 'index.html'));
copyFileSync(join(ROOT, 'vite.config.js'), join(TMP, 'vite.config.js'));
copyFileSync(join(ROOT, 'package.json'), join(TMP, 'package.json'));
const baseline = hashTree(TMP);

const fixtureDir = join(TMP, 'src/scenarios/check_fixture_scn');
mkdirSync(fixtureDir, { recursive: true });
writeFileSync(join(fixtureDir, 'index.js'), `export default {
    id: 'check_fixture_scn',
    tradeCode: '999999',
    name: '自检临时场景',
    icon: 'file',
    description: 'check.mjs 临时场景：仅新增目录，不改任何已有文件',
    processDefinitionKey: 'check_sop',
    mobile: {},
    agent: {},
    resultFields: [],
    chips: []
};
`, 'utf8');
console.log('  · 已新增 src/scenarios/check_fixture_scn/index.js');

console.log('[check] 4/6 在副本上执行真实 vite 库构建');
const vitePkg = JSON.parse(readFileSync(join(ROOT, 'node_modules/vite/package.json'), 'utf8'));
const binRel = typeof vitePkg.bin === 'string' ? vitePkg.bin : vitePkg.bin.vite;
const viteBin = join(ROOT, 'node_modules/vite', binRel);
const build = spawnSync(process.execPath, [viteBin, 'build'], { cwd: TMP, encoding: 'utf8', timeout: 180000 });
if (build.status !== 0) {
    console.error(build.stdout || '');
    console.error(build.stderr || '');
}
ok(build.status === 0, '副本构建成功（退出码 0）');

console.log('[check] 5/6 断言「新目录自动注册 + 已有文件零改动」');
const outDir = join(TMP, 'dist');
const bundles = existsSync(outDir) ? readdirSync(outDir).filter((name) => name.endsWith('.js')) : [];
const bundleText = bundles.map((name) => readFileSync(join(outDir, name), 'utf8')).join('\n');
ok(bundles.length > 0, `构建产物生成（${bundles.join(', ') || '无'}）`);
ok(bundleText.includes('check_fixture_scn'), 'fixture 场景被 import.meta.glob 自动注册进产物');
ok(bundleText.includes('advanced_sop') && bundleText.includes('balance_sop') && bundleText.includes('password_reset_sop'),
    '既有三个场景（transfer / balance / password）仍在产物中');
const additions = [];
const tmpAfter = hashTree(TMP);
for (const [rel, digest] of tmpAfter) {
    if (!baseline.has(rel)) additions.push(rel);
}
const edited = diff(baseline, tmpAfter).filter((rel) => !rel.startsWith('删除'));
ok(edited.length === 0, `除新增场景目录外零改动${edited.length ? `（差异：${edited.join('、')}）` : ''}`);
ok(additions.every((rel) => rel.startsWith('src/scenarios/') || rel.startsWith('dist/')),
    `新增文件仅来自 fixture 场景与构建产物（${additions.filter((rel) => !rel.startsWith('dist/')).join('、')}）`);

console.log('[check] 6/6 仓库未被自检改动');
const repoAfter = hashTree(ROOT);
const repoDiff = diff(repoBefore, repoAfter);
ok(repoDiff.length === 0, `仓库快照一致${repoDiff.length ? `（差异：${repoDiff.join('、')}）` : ''}`);

rmSync(TMP, { recursive: true, force: true });

if (failures) {
    console.error(`\n[check] 失败：${failures} 项断言未通过。`);
    process.exit(1);
}
console.log('\n[check] 全部通过：新增场景目录即可注册，已有文件零改动。');
