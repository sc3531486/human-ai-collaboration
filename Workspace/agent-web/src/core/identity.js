/**
 * 坐席身份：单一事实源。
 *
 * 客户敏感信息**不写入地址栏**（agent_common.js 的原始约束）：此处只从地址栏读取
 * 「坐席自身的非敏感演示参数」（姓名/技能组/坐席号），客户数据一律走后端接口下发。
 * 同样的 because，恢复上次业务只存 processId，不存客户信息。
 */
import { bus, report } from './bus.js';
import { reactive } from 'vue';

const SESSION_AGENT_KEY = 'bank.agentId';
const SESSION_AUTH_KEY = 'bank.agent.authenticated';
const SESSION_LOGIN_NAME_KEY = 'bank.agent.loginName';

function readAgentId() {
    try {
        return sessionStorage.getItem(SESSION_AUTH_KEY) === 'true'
            ? (sessionStorage.getItem(SESSION_AGENT_KEY) || '') : '';
    } catch (error) {
        // 存储读取失败是真实故障：如实上报后再使用演示默认——模块求值期不能抛出（会白屏）。
        // 「演示默认本身是否保留」见交付报告待裁定项。
        report('坐席号读取', error, { sticky: true });
        return '';
    }
}

function persistAgentId(id) {
    try { sessionStorage.setItem(SESSION_AGENT_KEY, id); }
    catch (error) { report('坐席号保存', error, { sticky: true }); }   // 保存失败＝刷新后坐席号可能漂移，必须可见
}

export const identity = reactive({
    agentName: '',
    employeeNo: '', centerName: '', institutionName: '', positionName: '', csrfToken: '',
    loginName: (() => { try { return sessionStorage.getItem(SESSION_LOGIN_NAME_KEY) || ''; } catch (error) { report('登录账号读取', error); return ''; } })(),
    // 技能组决定队列路由：私行 / 金卡 / 普通。可由地址栏参数覆盖用于多坐席演示。
    agentSkills: [],
    agentId: readAgentId(),
    get peerId() { return `bank-${this.agentId.toLowerCase()}`; }
});

persistAgentId(identity.agentId);

export function setAgentIdentity(next = {}) {
    Object.assign(identity, next);
    if (next.agentId) persistAgentId(next.agentId);
    if (next.loginName) {
        try { sessionStorage.setItem(SESSION_LOGIN_NAME_KEY, next.loginName); }
        catch (error) { report('登录账号保存', error, { sticky: true }); }
    }
    bus.emit('identity:changed', { ...identity });
    return identity;
}

export function hasAgentSession() {
    try { return sessionStorage.getItem(SESSION_AUTH_KEY) === 'true' && Boolean(sessionStorage.getItem(SESSION_AGENT_KEY)); }
    catch (error) { report('坐席登录会话读取', error, { sticky: true }); return false; }
}

export function markAgentAuthenticated() {
    try { sessionStorage.setItem(SESSION_AUTH_KEY, 'true'); }
    catch (error) { report('坐席登录会话保存', error, { sticky: true }); }
}

export function clearAgentSession() {
    try {
        sessionStorage.removeItem(SESSION_AGENT_KEY);
        sessionStorage.removeItem(SESSION_AUTH_KEY);
        sessionStorage.removeItem(SESSION_LOGIN_NAME_KEY);
    } catch (error) { report('坐席登录会话清理', error, { sticky: true }); }
    identity.agentId = '';
    identity.agentName = '';
    identity.loginName = '';
    identity.agentSkills = [];
    identity.csrfToken = '';
    identity.employeeNo = ''; identity.centerName = ''; identity.institutionName = ''; identity.positionName = '';
}

export default identity;
