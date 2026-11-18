/**
 * 账户查询场景包（渠道端）。对齐 BPMN：BalanceSOP.bpmn20.xml。
 * 只定制客户侧的身份核验与结果播报确认节点，其余交给通用兜底。
 */
export default {
    id: 'balance',
    tradeCode: '200102',
    name: '账户查询',
    icon: 'wallet',
    description: '余额查询与结果播报',
    processDefinitionKey: 'balance_sop',

    mobile: {
        '数字人身份核实': () => import('./mobile/digital-identity.js'),
        '数字人播报结果': () => import('./mobile/broadcast-result.js'),
    },

    agent: {},

    resultFields: ['balanceResult', 'account_no', 'account_status'],
    chips: ['查询账户余额']
};
