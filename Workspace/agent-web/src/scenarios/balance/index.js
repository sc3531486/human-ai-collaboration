/**
 * 账户查询场景包（素材取自 nodes/balance-*.js）。
 * 只定制人工核验与结果确认两个节点，其余交给通用兜底。
 */
export default {
    id: 'balance',
    tradeCode: '200102',
    name: '账户查询',
    icon: 'wallet',
    description: '余额查询与结果播报',
    processDefinitionKey: 'balance_sop',

    agent: {
        '座席视频核实身份': () => import('./agent/BalanceVerify.vue'),
        '数字人播报结果': () => import('./agent/BalanceResult.vue')
    },

    mobile: {},
    resultFields: ['balanceResult', 'account_no', 'account_status'],
    chips: ['查询账户余额']
};
