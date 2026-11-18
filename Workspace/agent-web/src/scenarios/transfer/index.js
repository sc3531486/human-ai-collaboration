/**
 * 转账汇款场景包（material 取自 navis-web/public/js/nodes/transfer-*.js 与 views/）。
 *
 * 这里只登记**需要定制体验的节点**：其余节点（风控评估、核心转账、通知等）
 * 由通用兜底 GenericNode.vue 纯靠后端 nodePolicy 渲染，前端无需任何代码。
 */
export default {
    id: 'transfer',
    tradeCode: '300101',
    name: '转账汇款',
    icon: 'transfer',
    description: '大额转账视频核验',
    processDefinitionKey: 'advanced_sop',

    // 键用 BPMN 里的中文节点名（AdvancedSOP.bpmn20.xml：信息采集 / 人工授权 / 人工核实签名 …）。
    agent: {
        '信息采集': () => import('./agent/TransferCollect.vue'),
        '人工授权': () => import('./agent/TransferAuthorize.vue'),
        '人工核实签名': () => import('./agent/TransferSignatureReview.vue'),
        '转账结果': () => import('./agent/TransferResult.vue')
    },

    mobile: {},
    resultFields: ['payee', 'amount', 'purpose'],
    chips: ['给王总转账200万']
};
