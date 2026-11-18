/*
 * 对话区（源：mobile_balance.html sendMsg / addMsg + 留痕补拉的座席消息）。
 * 场景组件内禁止直接 fetch（契约 §2.2）——对话与补拉全部经 core/sdk.js。
 * 所有进入 DOM 的文本统一转义（契约 §3.7：用户与模型字符串一律 escape）。
 */
import { chat as chatApi, humanChat, evidence, newRequestId } from '../core/sdk.js';
import { escape } from '../core/format.js';
import { identity } from '../core/identity.js';

export function createChat(refs, options = {}) {
    const getConsent = options.getConsent || (() => false);
    const showConsent = options.showConsent || (() => {});
    const speech = options.speech;
    const onForm = options.onForm || (() => {});
    const onProcess = options.onProcess || (() => {});
    const onHold = options.onHold || (() => {});
    const onResume = options.onResume || (() => {});
    const onSegmentStart = options.onSegmentStart || (() => {});
    const setAvatarState = (state) => speech?.setAvatarState(state);

    let history = [];        // 送回后端的多轮上下文（content 为原始文本）
    let bizNo = '';
    /**
     * 对话默认模型名：传空串表示"由服务端决定"，实际取值来自
     * 服务端 `digital-human.llm.model`（支持 `${LLM_MODEL}` 覆盖）。
     *
     * 不要在客户端硬编码模型名：供应商端点（api.commandcode.ai/provider/v1）
     * 只接受带命名空间前缀的 id（如 `deepseek/deepseek-v4.1-flash`），
     * 且该字段会被随请求上送、覆盖服务端配置 —— 写死在客户端会让"改服务端配置"失效，
     * 换模型时还必须重新构建并分发 SDK。
     */
    let model = '';
    let agentTimer = null;
    let agentCursor = 0;
    const received = new Set();
    let disposed = false;
    let pendingHuman = null;

    /** 追加一条消息。text 一律按纯文本处理，内部转义后进 DOM。 */
    function addMsg(type, text) {
        const div = document.createElement('div');
        const kind = ['user', 'ai', 'system', 'agent'].includes(type) ? type : 'system';
        div.className = `vb-msg vb-${kind}`;
        const plain = String(text ?? '');
        if (kind === 'ai') {
            const cleanForAttr = plain.replace(/<[^>]+>/g, '').replace(/["']/g, '').trim();
            const button = document.createElement('button');
            button.type = 'button';
            button.style.cssText = 'display:inline-block;margin-left:8px;background:rgba(37,99,235,0.08);border:1px solid #bfdbfe;color:#2563eb;border-radius:4px;font-size:11px;padding:2px 6px;cursor:pointer;';
            button.title = '点击重听数字人语音';
            button.textContent = '🔊 朗读';
            button.onclick = () => speech?.speak(cleanForAttr);
            div.innerHTML = `${escape(plain)} `;
            div.appendChild(button);
        } else {
            div.textContent = plain;
        }
        refs.chat.appendChild(div);
        refs.chat.scrollTop = refs.chat.scrollHeight;
        if (kind === 'ai') speech?.speak(plain);
        return div;
    }

    function quickFill(text) {
        const clean = String(text).replace(/^[\uD800-\uDBFF\uDC00-\uDFFF☀-➿\s]+/, '').trim();
        if (refs.input) refs.input.value = clean || text;
        sendMsg();
    }

    async function sendMsg() {
        if (disposed) return;
        options.primeAudio?.();
        if (!getConsent()) {
            showConsent();
            return;
        }
        const input = refs.input?.value;
        if (!input) return;

        const mode = options.getChatMode?.() || 'DIGITAL';
        if (mode === 'WAITING') {
            addMsg('system', '正在等待人工接听，请稍候。');
            return;
        }
        if (mode === 'HUMAN') {
            try {
                const processId = options.getProcessId?.();
                if (!pendingHuman || pendingHuman.processId !== processId || pendingHuman.text !== input) {
                    pendingHuman = { processId, text: input, eventId: newRequestId(), clientTime: new Date().toISOString() };
                }
                await humanChat({ ...pendingHuman, actorId: options.getPeerId?.() });
                pendingHuman = null;
                addMsg('user', input);
                if (refs.input.value === input) refs.input.value = '';
            } catch (error) { addMsg('system', `消息发送失败：${error.message}`); }
            return;
        }

        onHold();                    // 对话期间暂停轮询，避免旧快照覆盖聊天带回的表单

        addMsg('user', input);
        refs.input.value = '';
        setAvatarState('thinking');
        addMsg('system', '正在理解您的需求…');

        if (!bizNo) bizNo = identity.ensureBizNo();
        onSegmentStart(1, 'DIGITAL_HUMAN');

        try {
            await options.ready?.();
            const data = await chatApi({
                text: input,
                bizNo,
                processId: options.getProcessId?.() || '',
                history,
                peerId: options.getPeerId?.() || identity.ensure(),
                customerId: options.getCustomerId?.() || identity.ensure(),
                model,
            });
            // 人工入房期间到达的旧模型响应不得播报或覆盖人工会话。
            if ((options.getChatMode?.() || 'DIGITAL') !== 'DIGITAL') { onResume(); return; }

            if (data.bizNo) bizNo = data.bizNo;
            if (data.processId) await onProcess(data.processId);

            history.push({ role: 'user', content: input });
            history.push({
                role: 'assistant',
                content: JSON.stringify({
                    intent: data.intent || (data.action === 'transfer' ? 'TRANSFER' : 'QA'),
                    amount: data.amount,
                    payee: data.payee,
                    isModification: data.isModification,
                    slotsComplete: data.slotsComplete,
                    reply: data.reply,
                }),
            });

            addMsg('ai', data.reply || '请核对当前业务信息。');
            startAgentChat();
            if (data.action === 'form') {
                await onForm(data.state || {}, data.payload || {});
            } else {
                onResume();
            }
        } catch (error) {
            addMsg('system', `❌ 请求异常: ${error.message || error}`);
            setAvatarState('idle');
            onResume();
        }
    }

    /* 座席在留痕里下发的消息：2s 补拉一次（源：syncAgentChat）。 */
    async function syncAgentChat() {
        if (!bizNo) return;
        try {
            const rows = await evidence.trace(bizNo, agentCursor, 100);
            rows.forEach((row) => {
                agentCursor = Math.max(agentCursor, Number(row.id) || 0);
                receiveMessage(row);
            });
        } catch {
            /* 补拉失败等下一轮 */
        }
    }

    function startAgentChat() {
        if (agentTimer || disposed || !bizNo) return;
        syncAgentChat();
        agentTimer = setInterval(syncAgentChat, 2000);
    }

    function receiveMessage(row) {
        if (disposed || row.eventType !== 'CHAT_AGENT_MESSAGE') return;
        const key = row.eventId || row.id;
        if (received.has(key)) return;
        received.add(key);
        addMsg('agent', String(row.payload?.text || ''));
    }

    function stopAgentChat() {
        if (agentTimer) clearInterval(agentTimer);
        agentTimer = null;
    }

    function setModel(next) {
        if (next) model = next;
    }

    function dispose() {
        disposed = true;
        stopAgentChat();
        history = [];
    }

    return {
        addMsg,
        receiveMessage,
        sendMsg,
        quickFill,
        startAgentChat,
        stopAgentChat,
        setModel,
        dispose,
        get bizNo() { return bizNo; },
        set bizNo(value) { bizNo = value || ''; },
        get history() { return history; },
    };
}

export default createChat;
