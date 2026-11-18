/**
 * 密码服务场景包（渠道端）。对齐 BPMN：PasswordResetSOP.bpmn20.xml。
 * 客户选卡 / 拍摄介质 / 拍摄身份证均为客户侧节点；选卡与拍照交互由通用兜底承载，
 * 这里叠加场景提示；核验类节点（联网核查与人脸比对）由坐席端定制。
 */
export default {
    id: 'password',
    tradeCode: '200101',
    name: '密码服务',
    icon: 'lock',
    description: '密码重置与解锁',
    processDefinitionKey: 'password_reset_sop',

    mobile: {
        card_capture: () => import('./mobile/camera-capture.js'),
        id_front_capture: () => import('./mobile/camera-capture.js'),
        id_back_capture: () => import('./mobile/camera-capture.js'),
        customer_face_capture: () => import('./mobile/camera-capture.js'),
        id_capture: () => import('./mobile/id-capture.js'),
        sms_verify: () => import('./mobile/sms.js'),
        customer_sign: () => import('./mobile/customer-sign.js'),
        '客户选择银行卡': () => import('./mobile/select-card.js'),
        '客户拍摄介质照片': () => import('./mobile/media-capture.js'),
        '客户拍摄身份证正反面': () => import('./mobile/id-capture.js'),
    },

    agent: {},

    resultFields: ['resetCardMasked', 'resetMessage', 'resetReceipt', 'receiptPdfUrl'],
    chips: ['重置银行卡密码']
};
