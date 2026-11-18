/**
 * 转账汇款场景包（渠道端）。对齐 BPMN：AdvancedSOP.bpmn20.xml。
 * 只登记需要定制体验的客户侧节点；其余（风控评估、核心转账、通知等）
 * 由通用兜底 GenericNode.js 纯靠后端 nodePolicy 渲染，前端无需任何代码。
 */
export default {
    id: 'transfer',
    tradeCode: '300101',
    name: '转账汇款',
    icon: 'transfer',
    description: '大额转账视频核验',
    processDefinitionKey: 'advanced_sop',

    // 键用 BPMN 里的中文节点名（AdvancedSOP：信息采集 / 客户签名 …）。
    mobile: {
        '信息采集': () => import('./mobile/collect.js'),
        '客户签名': () => import('./mobile/customer-sign.js'),
    },

    // 坐席端（agent-web 侧）字段；本 SDK 不读。
    agent: {},

    resultFields: ['payee', 'amount', 'purpose'],
    chips: ['给王总转账200万']
};
