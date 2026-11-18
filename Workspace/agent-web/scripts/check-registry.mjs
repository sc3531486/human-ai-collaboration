/**
 * 场景注册自检（node 原生，无外部依赖）：`npm run check`
 *
 * 断言的核心命题（契约 §0.3「新增场景不改已有代码」）：
 *   1. 新建一个临时场景目录后，registry 能**自动**把它注册进来；
 *   2. 为此 `src/core`、`src/ui`、`src/views` 的 git diff 必须为空（本脚本用内容快照校验，
 *      不依赖 git，避免在非 git 工作区里失效）；
 *   3. 场景目录移除后 registry 回到原状，不残留。
 *
 * 由于 registry 用了 `import.meta.glob`（Vite 专有语法），node 无法直接执行，
 * 这里用一个最小 Vite 构建把 registry 打成 ESM，再在 node 里 import 断言。
 */
import { existsSync, mkdirSync, rmSync, readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'vite';
import vue from '@vitejs/plugin-vue';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = join(root, 'src');
const scenarios = join(src, 'scenarios');
const registryEntry = join(src, 'core', 'registry.js');
// 注意：目录名不得以下划线开头——registry 会跳过 `_` 开头的目录（模板/私有目录，如 _template），
// 临时场景必须能被自动注册才能验证 §0.3，故这里用不带 `_` 前缀的名字。
const tempScenario = join(scenarios, 'check_temp_scn');
const tempOut = join(root, '.check-out');
const tempEntry = join(root, '.check-registry-entry.js');

const failures = [];
let passed = 0;
function ok(condition, message) {
    if (condition) { passed++; console.log(`  ✓ ${message}`); }
    else { failures.push(message); console.error(`  ✗ ${message}`); }
}

/** 收集目录树的内容指纹：新建场景必须让 core/ui/views 一个字节都不变。 */
function snapshot(dir) {
    const result = {};
    const walk = current => {
        for (const name of readdirSync(current).sort()) {
            const full = join(current, name);
            if (statSync(full).isDirectory()) { walk(full); continue; }
            result[full.replace(root, '').replace(/\\/g, '/')] = readFileSync(full, 'utf8');
        }
    };
    if (existsSync(dir)) walk(dir);
    return result;
}
const guarded = ['src/core', 'src/ui', 'src/views'];

let compileSeq = 0;
async function compileRegistry() {
    // 用一个只导出 registry 的临时入口做最小构建，避免整工程构建拖慢自检。
    writeFileSync(tempEntry, `export * from './src/core/registry.js';\n`, 'utf8');
    // 每次换一个文件名：node 的 ESM 按 URL 缓存，同名重复 import 会命中上一次的模块，
    // 那样"新增场景后重新构建"就永远读不到新结果，自检会假通过。
    const fileName = `registry-${++compileSeq}`;
    await build({
        root,
        logLevel: 'error',
        configFile: false,
        // 场景清单里的 agent 映射指向 .vue，构建必须带上 vue 插件与 @ 别名才能解析。
        plugins: [vue()],
        resolve: { alias: { '@': src } },
        build: {
            outDir: tempOut,
            emptyOutDir: false,
            minify: false,
            lib: { entry: tempEntry, formats: ['es'], fileName },
            rollupOptions: { external: [] }
        }
    });
    return pathToFileURL(join(tempOut, `${fileName}.js`)).href;
}

async function main() {
    console.log('场景注册自检');
    console.log('\n[1] 基线：registry 能加载并列出已注册场景');
    rmSync(tempScenario, { recursive: true, force: true });
    let url = await compileRegistry();
    let registry = await import(url);
    const baseline = registry.scenarioIds.slice();
    ok(baseline.length >= 3, `已注册场景 ≥3 个：${baseline.join(', ')}`);
    // _template 是模板目录，不作为可办场景参与注册（下划线开头被跳过）。
    ok(!baseline.includes('_template'), '_template 模板目录未被注册为场景');
    for (const id of ['transfer', 'balance', 'password']) {
        ok(baseline.includes(id), `场景包 ${id} 已自动注册`);
    }

    console.log('\n[2] 受保护目录的内容快照');
    const before = {};
    guarded.forEach(rel => Object.assign(before, snapshot(join(root, rel))));
    ok(Object.keys(before).length > 0, `受保护文件已采样（${Object.keys(before).length} 个）`);

    console.log('\n[3] 新增一个临时场景目录（不改动任何已有文件）');
    mkdirSync(join(tempScenario, 'agent'), { recursive: true });
    writeFileSync(join(tempScenario, 'index.js'), `export default {\n    id: 'check_temp_scn',\n    tradeCode: '999999',\n    name: '自检临时场景',\n    icon: 'file',\n    description: 'check-registry 临时场景',\n    processDefinitionKey: 'check_sop',\n    agent: {},\n    mobile: {}\n};\n`, 'utf8');
    writeFileSync(join(tempScenario, 'agent', 'Temp.vue'), '<template><div>temp</div></template>\n', 'utf8');

    url = await compileRegistry();
    registry = await import(url);
    const after = registry.scenarioIds.slice();
    ok(after.includes('check_temp_scn'), '临时场景被 registry 自动注册');
    ok(after.length === baseline.length + 1, `场景数从 ${baseline.length} 增加到 ${after.length}`);

    const found = registry.getScenario('check_temp_scn');
    ok(found?.tradeCode === '999999', '临时场景的清单字段被正确解析');
    ok(found?.processDefinitionKey === 'check_sop', 'processDefinitionKey 被正确解析');
    ok(registry.resolveScenario({ processDefinitionKey: 'check_sop' })?.id === 'check_temp_scn', '可按 processDefinitionKey 定位场景');
    ok(registry.resolveScenario({ tradeCode: '999999' })?.id === 'check_temp_scn', '可按 tradeCode 定位场景');
    // 场景包没写 agent 映射时，组件解析返回 null（会落到通用兜底），不得抛错。
    ok(registry.resolveScenarioComponent({ nodeName: '任意节点' }) === null, '无定制组件的节点返回 null（交给通用兜底）');

    console.log('\n[4] 断言 core / ui / views 零改动');
    const after2 = {};
    guarded.forEach(rel => Object.assign(after2, snapshot(join(root, rel))));
    const changed = Object.keys(after2).filter(file => after2[file] !== before[file]);
    ok(changed.length === 0, `core/ui/views 无改动${changed.length ? `：${changed.join(', ')}` : ''}`);
    const lostKeys = Object.keys(before).filter(file => after2[file] === undefined);
    ok(lostKeys.length === 0, 'core/ui/views 文件未被删除');

    console.log('\n[5] 清理临时场景后回到原状');
    rmSync(tempScenario, { recursive: true, force: true });
    url = await compileRegistry();
    registry = await import(url);
    ok(JSON.stringify(registry.scenarioIds) === JSON.stringify(baseline), '移除目录后场景列表回到基线');

    console.log('\n[6] 契约 §2.9 / §8.4：业务侧不得接触视频内部实现');
    const forbidden = [
        { pattern: /getElementById\(\s*['"](customer-video|my-video)['"]\s*\)/, label: "getElementById('customer-video'|'my-video')" },
        { pattern: /\bsrcObject\b/, label: 'srcObject' },
        { pattern: /\bMediaStream\b/, label: 'MediaStream' },
        { pattern: /from\s+['"][^'"]*\/core\/media\//, label: "import ... from '@/core/media/**'" },
        { pattern: /\bMediaRecorder\b/, label: 'MediaRecorder' }
    ];
    const surface = ['src/scenarios', 'src/views', 'src/ui'];
    let hits = 0;
    for (const rel of surface) {
        const dir = join(root, rel);
        if (!existsSync(dir)) continue;
        const walk = current => {
            for (const name of readdirSync(current)) {
                const full = join(current, name);
                if (statSync(full).isDirectory()) { walk(full); continue; }
                if (!/\.(vue|js)$/.test(full)) continue;
                const text = readFileSync(full, 'utf8');
                forbidden.forEach(({ pattern, label }) => {
                    if (pattern.test(text)) {
                        hits++;
                        console.error(`    ${full.replace(root, '')} 命中禁用字样：${label}`);
                    }
                });
            }
        };
        walk(dir);
    }
    ok(hits === 0, `scenarios/views/ui 无视频内部实现耦合（命中 ${hits} 处）`);

    console.log('\n[7] 契约 §0.3：core/ui/views 不得硬编码场景名与节点名');
    const scenarioIds = baseline;
    let hardcode = 0;
    for (const rel of guarded) {
        const dir = join(root, rel);
        if (!existsSync(dir)) continue;
        const walk = current => {
            for (const name of readdirSync(current)) {
                const full = join(current, name);
                if (statSync(full).isDirectory()) { walk(full); continue; }
                if (!/\.(vue|js)$/.test(full)) continue;
                const text = readFileSync(full, 'utf8');
                // 场景 id 与交易代码都不得出现在内核里
                scenarioIds.forEach(id => {
                    if (new RegExp(`['"\`]${id}['"\`]`).test(text)) {
                        hardcode++;
                        console.error(`    ${full.replace(root, '')} 硬编码场景 id：${id}`);
                    }
                });
                if (/300101|200102|200101/.test(text)) {
                    hardcode++;
                    console.error(`    ${full.replace(root, '')} 硬编码交易代码`);
                }
                ['信息采集', '人工授权', '人工核实签名', '座席视频核实身份', '人工核实手持证件并授权'].forEach(node => {
                    if (text.includes(node)) {
                        hardcode++;
                        console.error(`    ${full.replace(root, '')} 硬编码节点名：${node}`);
                    }
                });
            }
        };
        walk(dir);
    }
    ok(hardcode === 0, `core/ui/views 无场景名/节点名/交易代码硬编码（命中 ${hardcode} 处）`);

    console.log('\n[8] 契约 §2.9.3：适配层与控件产物必须提供完整命令 API');
    // 业务侧唯一入口（适配层）与宿主依赖的 dist 产物都要能在 node 里加载：
    // 适配层加载器全惰性（无顶层媒体/DOM 副作用），dist/vb-video.esm.js 是纯 ESM。
    const adapter = await import(pathToFileURL(join(src, 'core', 'video-widget.js')).href).catch(() => null);
    const widget = await import(pathToFileURL(resolve(root, '..', 'video-widget', 'dist', 'vb-video.esm.js')).href).catch(() => null);
    const api = ['open', 'close', 'connect', 'hangup', 'takeover', 'toggle', 'capture', 'record', 'on', 'getState'];
    const missingOf = surface => api.filter(name => name === 'record'
        ? !surface?.video?.record
        : typeof surface?.video?.[name] !== 'function');
    const missing = [
        ...missingOf(adapter).map(name => `适配层:${name}`),
        ...missingOf(widget).map(name => `控件产物:${name}`)
    ];
    ok(missing.length === 0, `命令 API 齐全（适配层 + 控件产物）${missing.length ? `，缺失：${missing.join(', ')}` : ''}`);
    ok(typeof adapter?.video?.record?.seal === 'function' && typeof widget?.video?.record?.seal === 'function',
        'record.seal() 存在（§3.4 封存回执依赖）');

    rmSync(tempOut, { recursive: true, force: true });
    rmSync(tempEntry, { force: true });

    console.log(`\n结果：${passed} 项通过，${failures.length} 项失败`);
    if (failures.length) {
        console.error(failures.map(item => `  - ${item}`).join('\n'));
        process.exit(1);
    }
    console.log('自检通过：新增场景无需改动 core/ui/views。');
}

main().catch(error => {
    console.error(error);
    rmSync(tempOut, { recursive: true, force: true });
    rmSync(tempEntry, { force: true });
    rmSync(tempScenario, { recursive: true, force: true });
    process.exit(1);
});
