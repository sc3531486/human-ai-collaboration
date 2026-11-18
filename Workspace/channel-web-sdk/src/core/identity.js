/* 端身份：客户侧标识与会话编号。客户敏感信息不写入地址栏（契约 §3.7）。 */

const PREFIX = 'cust-';

function randomSuffix(length) {
    const crypto = typeof window !== 'undefined' ? window.crypto : null;
    if (crypto && typeof crypto.getRandomValues === 'function') {
        const buffer = new Uint32Array(1);
        crypto.getRandomValues(buffer);
        return String(buffer[0] % Math.pow(10, length)).padStart(length, '0');
    }
    return String(Math.floor(Math.random() * Math.pow(10, length))).padStart(length, '0');
}

/** 与 navis-web 同口径：cust- + 6 位随机。可用 SDK 配置的 customerId 覆盖。 */
export function randomClientId() {
    return `${PREFIX}${randomSuffix(6)}`;
}

/** 与 navis-web 同口径：BIZ + yyyyMMddHHmmss + 3 位随机。 */
export function newBizNo() {
    const pad = (part) => String(part).padStart(2, '0');
    const now = new Date();
    const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    return `BIZ${stamp}${randomSuffix(3)}`;
}

export const identity = {
    clientId: '',
    /** 会话流水号：首次交互时确定，之后贯穿全部分段录像与留痕。 */
    bizNo: '',
    channel: '',

    ensure(config) {
        if (!this.clientId) this.clientId = (config && config.customerId) || randomClientId();
        if (!this.channel) this.channel = (config && config.channel) || 'Mobile App';
        return this.clientId;
    },

    ensureBizNo() {
        if (!this.bizNo) this.bizNo = newBizNo();
        return this.bizNo;
    },

    reset() {
        this.clientId = '';
        this.bizNo = '';
    },
};

export default identity;
