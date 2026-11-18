/**
 * 密码服务场景包（素材取自 nodes/password-*.js 与 verification-elements.js）。
 *
 * 核验类节点（介质卡号比对、联网核查与人脸比对）展示的是结构化 OCR / 比对结果，
 * 属于"定制体验"范畴；其余节点（客户选择银行卡、数字人办理重置等）走通用兜底。
 */
export default {
    id: 'password',
    tradeCode: '200101',
    name: '密码服务',
    icon: 'lock',
    description: '密码重置与解锁',
    processDefinitionKey: 'password_reset_sop',

    agent: {
        face_capture: () => import('./agent/PasswordCapture.vue'),
        verification_review: () => import('./agent/PasswordReview.vue'),
        signature_review: () => import('./agent/PasswordSignatureReview.vue'),
        '数字人校验介质卡号': () => import('./agent/PasswordMedia.vue'),
        '数字人联网核查与人脸比对': () => import('./agent/PasswordIdentity.vue'),
        '人工核实手持证件并授权': () => import('./agent/PasswordAuthorize.vue')
    },

    mobile: {},
    resultFields: ['resetCardMasked', 'resetMessage', 'resetReceipt', 'receiptPdfUrl'],
    chips: ['重置银行卡密码']
};
