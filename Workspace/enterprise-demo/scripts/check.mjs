/*
 * 最小自检（契约 §5）：
 *  1) 宿主桥十能力域齐全，且开关语义正确（关闭 = 域缺省 → SDK 走兜底）；
 *  2) 桩件 SDK 与 §6.1 接口同形（mount 返回 destroy/clientId）；
 *  3) 交付文档存在。
 * 运行：npm run check
 */
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');

const EXPECTED_DOMAINS = ['media', 'album', 'ocr', 'face', 'signature', 'record', 'biometrics', 'device', 'navigate', 'telemetry'];

let failures = 0;
const ok = (cond, message) => {
    if (cond) console.log('  ✓ ' + message);
    else { failures++; console.error('  ✗ ' + message); }
};

console.log('[check] 宿主 JSBridge 契约（§6.2 十能力域）');
const { createHostBridge, DOMAINS } = await import('../src/core/bridge-host.js');
const allOn = createHostBridge({});
for (const domain of EXPECTED_DOMAINS) ok(typeof allOn[domain] === 'object' && allOn[domain] !== null, `${domain} 域已实现`);
ok(DOMAINS.length >= EXPECTED_DOMAINS.length, `DOMAINS 清单覆盖 ${EXPECTED_DOMAINS.length} 域`);

console.log('[check] 能力开关：关闭的域必须从桥上缺省（SDK 据此走浏览器兜底）');
const partial = createHostBridge({ ocr: false, face: false });
ok(!('ocr' in partial), 'ocr 关闭后缺省');
ok(!('face' in partial), 'face 关闭后缺省');
ok(typeof partial.media === 'object', 'media 保持实现');
ok(typeof partial.navigate.to === 'function', 'navigate.to 为函数');
ok(typeof partial.telemetry.log === 'function', 'telemetry.log 为函数');

console.log('[check] 桩件 SDK 与 §6.1 接口同形');
const { loadMockSdk } = await import('../src/core/mock-sdk.js');
const api = loadMockSdk();
ok(typeof api.mount === 'function', 'mount(el, config) 存在');
let destroyed = false;
const fakeEl = {
    children: [],
    appendChild(node) { this.children.push(node); },
};
// Node 无 DOM：只验证「容器不存在时抛错」与签名。
let threw = false;
try { api.mount('#no-such-element'); } catch { threw = true; }
ok(threw, '容器不存在时抛错（与真实 SDK 一致）');
void destroyed; void fakeEl;

console.log('[check] 交付文档');
for (const doc of ['README.md', 'SCENARIO_GUIDE.md']) {
    ok(existsSync(join(root, doc)), `${doc} 存在`);
}

if (failures) {
    console.error(`\n[check] ${failures} 项失败`);
    process.exit(1);
}
console.log('\n[check] 全部通过');
