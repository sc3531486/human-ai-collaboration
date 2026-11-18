/*
 * 场景自动注册中心（契约 §2）。
 *
 * 用 import.meta.glob 收集 src/scenarios/*\/index.js —— 新增场景只需要在 scenarios/ 下加目录，
 * core/、ui/ 与打包配置全部不感知场景数量。
 * 缺 agent 或 mobile 字段的场景在另一端自动跳过，不报错。
 */

const modules = import.meta.glob('../scenarios/*/index.js', { eager: true });

function normalize(id, module) {
    const manifest = module?.default;
    if (!manifest || typeof manifest !== 'object') return null;
    return {
        id: manifest.id || id,
        tradeCode: manifest.tradeCode || '',
        name: manifest.name || id,
        icon: manifest.icon || 'file',
        description: manifest.description || '',
        processDefinitionKey: manifest.processDefinitionKey || '',
        // 本端（渠道 SDK）忽略 agent 字段；坐席端 agent-web 反之——缺字段一端自动跳过。
        agent: manifest.agent || {},
        mobile: manifest.mobile || {},
        chips: manifest.chips || [],
        resultFields: manifest.resultFields || [],
    };
}

export const list = Object.keys(modules)
    .map((path) => {
        const id = path.split('/').slice(-2)[0];
        // 下划线开头的目录（_template）只作模板，不进入场景列表，也不参与快捷问法汇总。
        return id.startsWith('_') ? null : normalize(id, modules[path]);
    })
    .filter((item) => Boolean(item))
    .sort((left, right) => left.id.localeCompare(right.id));

/** 本端（SDK 客户侧）读取 mobile 字段；agent 端读 agent 字段。 */
const SIDE = 'mobile';

const byId = new Map(list.map((item) => [item.id, item]));
const byKey = new Map(
    list.filter((item) => item.processDefinitionKey).map((item) => [item.processDefinitionKey, item]),
);

export const registry = {
    list,
    get: (id) => byId.get(id) || null,
    byProcessDefinitionKey: (key) => (key ? byKey.get(key) || null : null),
    /** 本端节点名 → 组件加载器；未命中返回 null。 */
    node(scenarioId, nodeName) {
        const scenario = byId.get(scenarioId);
        return scenario?.[SIDE]?.[nodeName] || null;
    },
    /** 该场景在本端声明的全部节点键，用于 nodeId 兜底命中。 */
    nodeKeys(scenarioId) {
        const scenario = byId.get(scenarioId);
        return Object.keys(scenario?.[SIDE] || {});
    },
    /** 数字人快捷问法：来自各场景 chips，服务端配置优先（契约 §3.6 动态加载）。 */
    chips() {
        return list.reduce((all, item) => all.concat(item.chips || []), []);
    },
};

export default registry;
