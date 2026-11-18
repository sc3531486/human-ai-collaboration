/**
 * 场景自动注册中心。
 *
 * **这里是"新增场景不改已有代码"的唯一入口**：用 import.meta.glob 急切收集
 * 场景根目录下每层的 index.js，任何已有文件都不需要改。新增场景只需在该目录加一个
 * index.js（可从 _template 复制改三处），构建期即被收集。
 *
 * 目录以下划线开头（如 _template）视为模板/私有目录，不参与注册。
 */
const modules = import.meta.glob('../scenarios/*/index.js', { eager: true });

/**
 * 清单校验：**缺字段直接抛错，不猜测、不补默认**。
 * 必填项 = 契约 §2 清单字段里决定路由与展示的那些（chips/resultFields 契约标注可选；
 * agent/mobile 契约 §2.3 规定缺省时对端自动跳过，故保留空对象语义）。
 * 无效清单若被"跳过"，会在运行时表现为该交易凭空消失——那是最难排查的静默故障，
 * 因此这里在注册期（构建/自检 import 时）就失败。
 */
const REQUIRED_FIELDS = ['id', 'tradeCode', 'name', 'icon', 'description', 'processDefinitionKey'];

function normalize(raw, file) {
    if (!raw) throw new Error(`[registry] 场景清单为空：${file}`);
    const manifest = typeof raw === 'function' ? raw : raw.default || raw;
    if (!manifest || typeof manifest !== 'object') throw new Error(`[registry] 场景清单不是对象：${file}`);
    const missing = REQUIRED_FIELDS.filter(field => manifest[field] === undefined || manifest[field] === null || manifest[field] === '');
    if (missing.length) throw new Error(`[registry] 场景清单缺少必填字段 ${missing.join('、')}：${file}`);
    return {
        id: String(manifest.id),
        tradeCode: String(manifest.tradeCode),
        name: String(manifest.name),
        icon: String(manifest.icon),
        description: String(manifest.description),
        processDefinitionKey: String(manifest.processDefinitionKey),
        // 坐席端 / 客户端两侧各取所需；缺字段的另一端自动跳过（契约 §2），不报错。
        agent: manifest.agent ?? {},
        mobile: manifest.mobile ?? {},
        chips: manifest.chips ?? [],
        resultFields: manifest.resultFields ?? []
    };
}

function collect() {
    const map = new Map();
    Object.entries(modules).sort(([a], [b]) => a.localeCompare(b)).forEach(([file, raw]) => {
        const directory = file.split('/').slice(-2)[0];
        // 下划线开头的目录是模板与内部素材，不作为可办场景。
        if (directory.startsWith('_')) return;
        const manifest = normalize(raw, file);
        if (map.has(manifest.id)) {
            // 重复 id 会让后注册者静默顶掉先注册者：路由与展示各走一边，必须立即失败。
            throw new Error(`[registry] 场景 id 重复：${manifest.id}（${file} 与已注册清单冲突）`);
        }
        map.set(manifest.id, manifest);
    });
    return map;
}

const scenarios = collect();

/** 全部已注册场景（按 id 升序，保证渲染顺序稳定不随文件系统抖动）。 */
export const scenarioList = Array.from(scenarios.values()).sort((a, b) => a.id.localeCompare(b.id));

export const scenarioIds = scenarioList.map(item => item.id);

export function getScenario(id) {
    return scenarios.get(id) || null;
}

/** 按流程定义键定位场景：同一流程多场景时按 tradeCode 精确隔离。 */
export function resolveScenario({ processDefinitionKey, tradeCode } = {}) {
    if (tradeCode) {
        const byTrade = scenarioList.find(item => String(item.tradeCode) === String(tradeCode));
        if (byTrade) return byTrade;
    }
    if (!processDefinitionKey) return null;
    const matched = scenarioList.filter(item => item.processDefinitionKey === processDefinitionKey);
    // 未填 tradeCode 的快照：仅在唯一命中时采用，多个候选时不猜。
    return matched.length === 1 ? matched[0] : null;
}

/**
 * 节点组件解析顺序（契约 §2.1）——必须严格按此三段递进：
 *   1) 场景包内按 nodeName 精确命中
 *   2) 场景包内按 nodeId 命中
 *   3) processDefinitionKey / tradeCode 命中的场景包（同一流程多场景隔离）
 * 未命中返回 null，由 router 落到通用兜底组件。
 */
export function resolveScenarioComponent(state = {}, preferred) {
    const candidates = [];
    if (preferred) candidates.push(preferred);
    const derived = resolveScenario(state);
    if (derived && derived !== preferred) candidates.push(derived);
    for (const scenario of candidates) {
        const nodes = scenario?.agent || {};
        if (state.nodeName && nodes[state.nodeName]) return nodes[state.nodeName];
        if (state.nodeId && nodes[state.nodeId]) return nodes[state.nodeId];
    }
    return null;
}

export default { scenarioList, scenarioIds, getScenario, resolveScenario, resolveScenarioComponent };
