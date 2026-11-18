/**
 * 坐席工作台状态：签入、振铃、接听、队列、标签互斥、历史、统计。
 *
 * 这里集中了契约 §3.5 的坐席端红线，逐条都有对应的业务原因注释：
 *  - 振铃：`hideRing(processId)` 只在事件 processId 命中当前振铃工单时收起；
 *  - 同一时刻只允许一个接听入口（振铃横条出现时队列浮层与浮动等待钮必须让位）；
 *  - 签入状态只走 HTTP 回写；
 *  - 关闭业务标签必须调 /close 终结实例；
 *  - 左栏互斥成对切换。
 *
 * 本 store **不感知任何场景**：所有业务语义来自场景清单与流程快照。
 */
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { request, flow as flowApi } from '@/core/sdk.js';
import { identity, setAgentIdentity } from '@/core/identity.js';
import { bus, report } from '@/core/bus.js';
import { flowState, setConnection } from '@/core/flow/store.js';
import * as router from '@/core/flow/router.js';
import * as sync from '@/core/flow/sync.js';
import { realtime } from '@/core/realtime/centrifuge.js';
import { video } from '@/core/video-widget.js';
import { resolveScenario } from '@/core/registry.js';
import { trace } from '@/core/trace.js';

const PROCESS_SESSION_KEY = 'bank.processId';
const ONLINE_SESSION_KEY = 'bank.online';
const SIGNIN_TIMER_KEY = 'bank.signInAt';

// 视频呈现由业务系统决定，控件只执行 open/connect/hangup/unmount。
// 当前坐席采用临时窗口；常驻渠道可替换为 persistent，并在初始化时 open、挂断时不 unmount。
const VIDEO_WINDOW_POLICY = Object.freeze({
    mode: 'ephemeral',
    layout: { left: 0.55, top: 0.07, width: 0.22, height: 0.235 },
    draggable: true
});

export const useWorkspaceStore = defineStore('workspace', () => {
    /* ---- 视图分支 ---- */
    const view = ref('home');            // home | business
    const online = ref(false);
    const businessTabVisible = ref(false);

    /* ---- 顶栏四态：offline / online / ringing / video ---- */
    const headerState = ref('offline');

    /* ---- 队列与振铃 ---- */
    const queue = ref([]);
    const ringing = ref(null);
    const queuePopClosed = ref(false);
    let answering = false;

    /* ---- 业务流程 ---- */
    const business = ref(null);          // 当前流程快照（只读语义）
    const connection = ref('connecting');
    const callSession = ref(null);
    const endedCalls = new Set();

    /* ---- 首页数据 ---- */
    const trades = ref([]);
    const notices = ref([]);
    const history = ref([]);
    const historyTotal = ref(0);
    const historyLegacy = ref(false);
    const page = ref(1);
    // 首页默认按 10 条呈现，充分利用桌面工作区高度；坐席仍可在分页控件切换条数。
    const pageSize = ref(10);
    const metricsToday = ref({ total: '—', completed: '—' });
    const stats = ref({ signInSeconds: 0, breakSeconds: 0, serviceSeconds: 0, averageServiceSeconds: 0, servedCount: 0, successCount: 0 });

    /* ---- 文字聊天：只记录系统真实事件，不伪造客户消息 ---- */
    const chat = ref([]);
    const conversation = ref([]);

    /* ----  timers 句柄 ---- */
    let ringTimer = null;
    let headerTimer = null;
    let queueTimer = null;
    let metricsTimer = null;
    let conversationTimer = null;
    let conversationCursor = 0;
    let conversationBizNo = '';
    const conversationReceived = new Set();
    let chatNodeKey = '';
    let offVideoState = null;
    /** AGENT_RELEASED 去重：同一单只收尾一次（事件可能因重连/重放重复到达）。 */
    let releasedProcessId = '';

    const agentNumber = computed(() => String(identity.agentId || '').match(/\d+/)?.[0] || identity.agentId || '—');
    const queueCandidates = computed(() => queue.value.filter(item => item.processId !== business.value?.processId));
    // 同一时刻只允许一个接听入口：有振铃时队列浮层与浮动等待钮必须让位。
    const hasActiveRing = computed(() => Boolean(ringing.value?.processId));
    const queuePopVisible = computed(() => online.value && queueCandidates.value.length > 0 && !queuePopClosed.value && !hasActiveRing.value);
    const floatWaitVisible = computed(() => online.value && !hasActiveRing.value);
    const businessTitle = computed(() => business.value?.sceneName || '业务办理');
    const steps = computed(() => (business.value?.steps || []).map(step => ({ ...step })));

    /* ================= 工具：聊天与提示 ================= */
    function pushChat(message, role = 'SYSTEM') {
        if (!message) return;
        const now = new Date();
        const time = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`;
        chat.value = [...chat.value, { time, text: message, role }].slice(-50);
    }
    function toast(message, error = false) {
        bus.emit('ui:toast', { message, error });
    }

    /* ================= 顶栏状态机 ================= */
    const HEADER_LABELS = { offline: '未签入', online: '就绪中', ringing: '振铃中', video: '视频中' };
    function setHeaderState(next) {
        if (!['offline', 'online', 'ringing', 'video'].includes(next)) return;
        headerState.value = next;
        if (bus.emit) bus.emit('header:state', { state: next, label: HEADER_LABELS[next] });
    }

    /* ================= 左栏互斥（§3.5 红线）=================
     * 办理中显示办理人信息，其余显示坐席控制台。两者共用左栏同一格，
     * 必须成对切换——集中在此避免两处各写一遍导致两个面板同时占位或同时消失。
     */
    function leftPanel(isBusiness) {
        bus.emit('layout:left-panel', isBusiness);
    }

    /* ================= 签入 / 签出 ================= */
    let signinTimerReported = false;
    function startHeaderTimer() {
        try {
            if (!Number(sessionStorage.getItem(SIGNIN_TIMER_KEY))) sessionStorage.setItem(SIGNIN_TIMER_KEY, String(Date.now()));
        } catch (error) {
            // 存储不可用只影响计时持久化，不阻断签入，但失败必须可见（不许静默退化）。
            report('签入计时保存', error);
        }
        paintHeaderTimer();
        if (!headerTimer) headerTimer = setInterval(paintHeaderTimer, 1000);
    }
    function paintHeaderTimer() {
        let began = 0;
        try { began = Number(sessionStorage.getItem(SIGNIN_TIMER_KEY)) || 0; }
        catch (error) {
            // 本函数每秒执行：只在首次失败时上报，避免 console/system:error 被同一故障刷屏。
            if (!signinTimerReported) { signinTimerReported = true; report('签入计时读取', error, { sticky: true }); }
        }
        bus.emit('header:timer', began ? Math.floor((Date.now() - began) / 1000) : 0);
    }
    function stopHeaderTimer() {
        clearInterval(headerTimer);
        headerTimer = null;
        try { sessionStorage.removeItem(SIGNIN_TIMER_KEY); } catch (error) { report('签入计时清理', error); }
        bus.emit('header:timer', 0);
    }

    /**
     * 签入 / 签出：后端只把 ONLINE 坐席纳入队列路由，因此状态**必须经 HTTP 回写**（§3.5）。
     * 实时通道只承载下行推送，不承载签入语义。
     *
     * 回写成功才落本地状态：setStatus 失败**照常抛出**（错误码 + 人话经调用方 / 全局
     * errorHandler 呈现）。若先翻本地态再吞掉失败，坐席界面显示「已签入」而服务端与
     * 心跳仍是旧状态——一直等不到单且无从察觉，正是要清除的带病流程。
     */
    async function setOnline(next) {
        await realtime.setStatus(next ? 'ONLINE' : 'OFFLINE');
        online.value = next;
        try { sessionStorage.setItem(ONLINE_SESSION_KEY, String(next)); }
        catch (error) { report('签入状态本地保存', error); }
        setHeaderState(next ? 'online' : 'offline');
        if (next) startHeaderTimer(); else stopHeaderTimer();
        if (next) await refreshQueue();
        else {
            hideRing();
            queue.value = [];
            queuePopClosed.value = false;
            renderQueue();
        }
    }
    async function signIn() { if (!online.value) await setOnline(true); }
    async function logout() {
        if (callSession.value?.status === 'OPEN') return toast('请先挂断当前通话再签退', true);
        if (business.value?.processStatus === 'RUNNING') return toast('请先完成当前业务再签退', true);
        await request('/api/platform/agent-logout', {});
        online.value = false;
        dispose();
        bus.emit('auth:logout');
    }
    async function signOut() {
        if (!online.value) return;
        if (callSession.value?.status === 'OPEN') return toast('请先挂断当前视频通话再签出', true);
        // 尚有进行中的业务时拒绝签出：否则名下工单会被回收而坐席还停在界面上。
        if (business.value?.processStatus === 'RUNNING') return toast('当前业务尚未结束，请完成办理后签出', true);
        await setOnline(false);
    }

    /* ================= 队列 ================= */
    let queueBusy = false;
    async function refreshQueue() {
        if (!online.value || queueBusy) return;
        queueBusy = true;
        try {
            const result = await request(`/api/platform/agent/${encodeURIComponent(identity.agentId)}/calls`);
            const summary = result?.queue;
            if (!Array.isArray(summary?.queue)) throw new Error('路由队列响应缺少 queue 列表');
            queue.value = summary.queue;
            if (!answering) {
                if (summary.ringing) showRing(summary.ringing);
                else if (ringing.value) hideRing(null, ringing.value.processId);
            }
            renderQueue();
        } catch (error) {
            queue.value = [];
            bus.emit('queue:error');   // 事件钩子保留；当前无监听者，呈现由下方 report 保证
            report('待接队列刷新', error, { sticky: true });
        } finally { queueBusy = false; }
    }
    function renderQueue() {
        bus.emit('queue:render', { candidates: queueCandidates.value });
    }
    function toggleQueuePop() {
        if (hasActiveRing.value) return;
        queuePopClosed.value = !queuePopClosed.value;
    }

    /* ================= 来电振铃（§3.5 红线）================= */
    function showRing(ticket) {
        if (!ticket?.processId) return;
        // 断线轮询会重复推送同一张工单，仅在工单变化时写入聊天记录。
        const fresh = ringing.value?.ticketId !== ticket.ticketId;
        ringing.value = ticket;
        setHeaderState('ringing');
        if (fresh) pushChat(`收到客户「${ticket.customerName || ticket.vars?.customer_name || '客户'}」的来电振铃`);
        clearInterval(ringTimer);
        const began = ticket.ringStartedAt || Date.now();
        const tick = () => {
            const seconds = Math.max(0, Math.round((Date.now() - began) / 1000));
            bus.emit('ring:wait', seconds);
            // 服务端振铃超时（45 秒）后工单回到等待队列，横条同步收起。
            if (seconds > 50) hideRing('来电已超时，已退回等待队列');
        };
        ringTimer = setInterval(tick, 1000);
        tick();
    }

    /**
     * 收起振铃。**必须在 U 型口径下只认本工单 processId**：
     * 实时频道会广播其他客户的接听事件；广播里没有 agentId 时也携带 processId，
     * 凭 processId 不匹配直接返回，否则一个无关广播会把当前来电降级成队列卡。
     */
    function hideRing(message, processId) {
        if (processId && ringing.value?.processId && processId !== ringing.value.processId) return;
        clearInterval(ringTimer);
        ringTimer = null;
        ringing.value = null;
        setHeaderState(online.value ? 'online' : 'offline');
        renderQueue();
        if (message) toast(message);
    }

    async function rejectCall(processId) {
        if (!processId) return hideRing();
        try {
            await request(`/api/platform/call/${encodeURIComponent(processId)}/reject`, { agentId: identity.agentId });
            // 验证期后端稳定的部分契约（缺 ProcessId 时会让 InCall 卡住不消失）会影响 UI 断言，
            // 因此这里显式带本机 processId 收起这一次来电。
            hideRing('已拒接，来电转交其他坐席', processId);
            pushChat('已拒接本次来电，工单转交其他坐席');
            await refreshQueue();
        } catch (error) {
            // 拒接失败：振铃横条保持展开（hideRing 只在成功分支内），错误带码如实呈现。
            report('拒接来电', error);
        }
    }

    /**
     * 接听即绑定业务。
     *
     * 人工接入的三分屏录制需要接听点击授权桌面采集。
     * 业务只走路由接听；工单缺失、过期或归属冲突必须失败，不能改走流程认领。
     */
    async function answerCall(processId) {
        if (callSession.value?.status === 'OPEN') throw new Error('当前视频通话尚未挂断');
        if (!processId) throw new Error('来电已失效，请刷新接待队列');
        if (business.value && business.value.processStatus !== 'COMPLETED') throw new Error('请先完成当前业务，再接听其他客户');
        // 对齐基线：上一段录像还在补传时不再接听，避免两段存证互相覆盖。
        if (video.getState().recording) throw new Error('请先上传当前录像');
        if (answering) throw new Error('正在接听，请稍候');
        answering = true;
        try {
        const mediaUser = await request('/api/media/users', { role: 'AGENT', subject: identity.agentId });
        const localPeerId = mediaUser.peerId;
        await realtime.ready(mediaUser);
        await video.prepare({ localPeerId, processId });
        const state = await request(`/api/platform/call/${encodeURIComponent(processId)}/answer`, {
                agentId: identity.agentId, peerId: localPeerId
            });
        callSession.value = await request(`/api/calls/by-process/${encodeURIComponent(processId)}`);
        if (!callSession.value?.callId) throw new Error('服务端未建立通话会话');
        hideRing();
        // 建链与录制起点必须留在本次接听点击的用户手势链路内（§3.4 桌面采集手势内申请；
        // §2.9.3 connect 为坐席端接听后调用）。peerId 取接听响应里的客户移动端标识，
        // 未绑定远端时控件自行走本地见证分支。任一步失败只如实提示——
        // 无视频时业务必须仍能办理到底（§2.9.3）。
        let mediaReady = false;
        try {
            // “接听”是唯一打开临时视频窗的时机。该调用仍处于用户手势链路内。
            await video.open({
                mode: 'overlay',
                draggable: VIDEO_WINDOW_POLICY.draggable,
                restore: false,
                layout: VIDEO_WINDOW_POLICY.layout
            });
            await video.connect({
                peerId: state.vars?.mobile_peer_id,
                localPeerId,
                processId: state.processId,
                participants: callSession.value.participants,
                dialAll: callSession.value.role === 'AUTHORIZER'
            });
            await video.record.start({ processId: state.processId, bizNo: state.bizNo, nodeName: state.nodeName });
            mediaReady = true;
        } catch (error) {
            error.message = `音视频接入失败：${error.message}`;
            throw error;
        }
        await bindBusiness(state);
        renderQueue();
        pushChat(`已接听客户来电，业务「${state.bizNo || state.processId}」已绑定`);
        // 音视频就绪后顶栏进入通话态；通话结束由 init 里的 on('state') 订阅回落。
        if (mediaReady) setHeaderState('video');
        return state;
        } catch (error) {
            // 仅清理本次接听建立的本地媒体资源，绝不改走其他业务路径。
            if (callSession.value?.status === 'OPEN') {
                try { await request(`/api/calls/${encodeURIComponent(callSession.value.callId)}/failed`, { actorId: identity.agentId, processId }); }
                catch (cleanupError) { report('接听失败服务端清理', cleanupError); }
                callSession.value = null;
            }
            try {
                if (video.getState().recording) await sealRecording();
            } catch (cleanupError) { report('接听失败录像封存', cleanupError); }
            try {
                if (VIDEO_WINDOW_POLICY.mode === 'ephemeral') await video.unmount();
                else await video.close();
            } catch (cleanupError) { report('接听失败资源清理', cleanupError); }
            throw error;
        } finally { answering = false; }
    }

    /* ================= 业务绑定 / 释放 ================= */
    function applyState(state) {
        // steps 是流程快照的端侧展示契约（CONTRACTS §四：nodeId/nodeName/… 恒定下发）。
        // 后端缺失即契约漂移——在唯一入口如实上报；各视图的 `|| []` 只兜渲染，不兜事实。
        if (!Array.isArray(state.steps)) {
            report('流程快照 steps 字段', new Error('快照缺少 steps 列表，与端侧展示契约不符'), { sticky: true });
        }
        business.value = state;
        const vars = state.vars || {};
        // 聊天记录只跟随真实节点变化写入，重复快照不重复记账。
        const key = `${state.processId}:${state.nodeId}:${state.processStatus}`;
        if (key !== chatNodeKey) {
            chatNodeKey = key;
            pushChat(state.processStatus === 'COMPLETED'
                ? `业务「${state.bizNo || state.processId}」已办结`
                : `业务节点更新为「${state.nodeName || state.nodeId}」`);
        }
        leftPanel(true);
        businessTabVisible.value = true;
        void vars;
    }

    async function bindBusiness(state) {
        business.value = state;
        // 新业务即新的释放观察窗口：上一单的 AGENT_RELEASED 去重标记必须清掉，
        // 否则同一坐席承接的下一单会被当成"已释放"而不收尾。
        releasedProcessId = '';
        try { sessionStorage.setItem(PROCESS_SESSION_KEY, state.processId); }
        catch (error) { report('业务恢复号保存', error); }   // 保存失败=刷新后无法自动恢复，必须可见
        // 场景包优先解析随业务确定（同一流程多场景隔离），核心 `router` 不感知场景名。
        router.preferScenario(resolveScenario(state));
        sync.start(state.processId, { connected: realtime.connected });
        router.bind(state.processId);
        // 首次校准后立刻路由一次，界面按节点自动还原。
        await router.accept(state, true);
        applyState(state);
        view.value = 'business';
        startConversationSync(state);
        return state;
    }

    async function startTrade(trade) {
        if (callSession.value?.status === 'OPEN') return toast('当前视频通话尚未结束，请先挂断', true);
        if (business.value && business.value.processStatus !== 'COMPLETED') {
            view.value = 'business';
            return toast('请先完成当前业务');
        }
        if (video.getState().recording) return toast('当前仍有录像待上传，请先处理', true);
        const state = await request('/api/platform/process', {
            agentId: identity.agentId, peerId: identity.peerId, customerName: '测试客户', tradeCode: trade?.tradeCode
        });
        await bindBusiness(state);
        return state;
    }

    /**
     * 封存当前录像段并按 §3.4 如实提示，永不抛出（封存问题不得阻断业务释放）：
     *  - 确有进行中的录制却封出 0 字节 → 报「未采集到画面」，不得宣告成功；
     *  - 没有进行中的录制（自办业务未建链 / 已在挂断时封存过）→ 不冒充失败去打扰坐席；
     *  - 封存抛错 → 「录像待补传」提示。
     */
    async function sealRecording() {
        const pending = video.getState().recording;
        try {
            const receipt = await video.record.seal();
            if (pending && receipt && receipt.fileSize === 0) {
                toast(receipt.message || '该段录像未采集到画面，请确认共享与摄像头状态', true);
            }
            return receipt;
        } catch (error) {
            // §3.4 口径不变：封存失败如实提示、永不抛出（不阻断业务释放）——呈现升级为带码 report。
            error.message = `录像待补传：${error.message}`;
            report('录像封存', error);
            return null;
        }
    }

    /**
     * 关闭业务标签＝结束本次业务：必须调 /close 终结实例。
     * 否则实例滞留并持续占据待接队列，下一次接听还会被「请先完成当前业务」挡住。
     */
    async function finish() {
        const current = business.value;
        if (current && current.processStatus !== 'COMPLETED') {
            try {
                await request(`/api/platform/process/${encodeURIComponent(current.processId)}/close`, {});
            } catch (error) {
                // /close 失败必须中止收尾（不 release、不 home）：否则实例滞留却显示已结单。
                // 带码呈现后返回，坐席可重试关闭。
                report('终结业务实例（/close）', error);
                return;
            }
        }
        await release();
        pushChat('本次业务已结束，通话已挂断');
        home();
        await Promise.all([loadHistory(), loadMetrics(), refreshQueue()]);
    }

    /** 释放本地绑定：不含 /close 语义（挂断/回放清理用）。 */
    async function release() {
        // 先拿到封存回执再 close：close 会停掉录制器，与进行中的封存并发会把回执写坏。
        const keepCall = callSession.value?.status === 'OPEN';
        if (!keepCall) {
            await sealRecording();
            if (VIDEO_WINDOW_POLICY.mode === 'ephemeral') await video.unmount();
            else await video.close();
        }
        sync.stop();
        router.bind('');
        router.releaseCurrent();
        business.value = null;
        businessTabVisible.value = false;
        try { sessionStorage.removeItem(PROCESS_SESSION_KEY); }
        catch (error) { report('业务恢复号清理', error); }   // 清理失败=下次打开会尝试恢复已结束业务，必须可见
        if (!keepCall) { clearInterval(conversationTimer); conversationTimer = null; }
        leftPanel(false);
        setHeaderState(keepCall ? 'video' : online.value ? 'online' : 'offline');
    }

    /**
     * AGENT_RELEASED 收尾（改造方案 §14.1 / §5.3）：坐席完成本单最后一个人工节点后，
     * 服务端释放人员租约并只投递一次释放事件。坐席据此封存录像、挂断并退出交易页，
     * 回到可接待状态；**不调 /close** —— 后续正式转账、回执与结果推送仍由流程继续走完，
     * 坐席只是不再参与（§5.3 第 5 条）。
     *
     * 事件可能重复到达（重连/重放），用 releasedProcessId 去重，避免重复封存。
     */
    async function releaseAfterAgentWork() {
        const current = business.value;
        if (!current) return;
        if (releasedProcessId === current.processId) return;
        releasedProcessId = current.processId;
        try {
            if(callSession.value?.role==='AUTHORIZER') {
                await request(`/api/calls/${encodeURIComponent(callSession.value.callId)}/leave`,{actorId:identity.agentId,processId:current.processId});
                callSession.value=null;
            }
            await release();
            pushChat('本单人工环节已完成，已交还流程自动办理并退出交易页');
        } catch (error) {
            // 收尾失败不静默：允许下一次事件重试（清掉去重标记），并如实上报。
            releasedProcessId = '';
            report('坐席释放收尾（AGENT_RELEASED）', error, { sticky: true });
            return;
        }
        await refreshQueue();
    }

    /** 挂断：只结束音视频，业务流程保持当前节点。 */
    async function hangup() {
        if (!callSession.value?.callId) throw new Error('当前没有可挂断的通话');
        if(callSession.value.role==='AUTHORIZER'){
            await request(`/api/calls/${encodeURIComponent(callSession.value.callId)}/leave`,{actorId:identity.agentId,processId:callSession.value.processId});
            await sealRecording();await video.unmount();callSession.value=null;setHeaderState(online.value?'online':'offline');return;
        }
        const event = await request(`/api/calls/${encodeURIComponent(callSession.value.callId)}/hangup`, {
            actorRole: 'AGENT', actorId: identity.agentId
        });
        await onCallEnded(event);
    }

    async function onCallEnded(event) {
        if (!event?.callId || event.callId !== callSession.value?.callId || endedCalls.has(event.callId)) return;
        const endedBusiness = business.value;
        endedCalls.add(event.callId);
        // 对齐基线顺序：先封存本段录像（stopAndUpload），再释放音视频（结束信令）。
        await sealRecording();
        await video.hangup();
        if (VIDEO_WINDOW_POLICY.mode === 'ephemeral') await video.unmount();
        callSession.value = null;
        // 通话结束即复位接管形态，下一通默认回到音视频接管（对齐基线挂断链路）。
        // 复位失败只上报不阻断挂断收尾（sticky 防每通刷屏）——控件未加载时的失败同样不得静默。
        try { await video.takeover('av'); }
        catch (error) { report('接管形态复位', error, { sticky: true }); }
        setHeaderState(online.value ? 'online' : 'offline');
        pushChat('通话已结束，业务流程保持当前状态');
        if (event.initiatorRole === 'CUSTOMER') bus.emit('ui:customer-hangup', '客户已挂断');
        // 服务小结属于坐席业务收尾：通话真正结束后再取服务快照，避免把未封存的通话当作已结束服务。
        if (event.initiatorRole === 'AGENT' || event.initiatorRole === 'CUSTOMER') {
            bus.emit('service-summary:open', { callId: event.callId, processStatus: endedBusiness?.processStatus });
        }
        await refreshQueue();
    }

    function home() {
        view.value = 'home';
    }
    function gotoBusiness() {
        if (!business.value) return;
        view.value = 'business';
    }

    /* ================= 文字聊天 ================= */
    function rowToEntry(row) {
        const payload = row.payload || {};
        const text = row.eventType === 'CHAT_REQUEST' ? payload.text
            : row.eventType === 'CHAT_REPLY' ? payload.reply
                : ['CHAT_AGENT_MESSAGE', 'CHAT_CUSTOMER_MESSAGE'].includes(row.eventType) ? payload.text : '';
        return text ? { id: row.id, role: row.actorRole, time: new Date(Number(row.createdAt)).toLocaleTimeString('zh-CN', { hour12: false }), text: String(text) } : null;
    }
    async function loadConversation(state = business.value || callSession.value) {
        if (!state?.bizNo) return;
        if (conversationBizNo !== state.bizNo) { conversationBizNo = state.bizNo; conversationCursor = 0; conversation.value = []; conversationReceived.clear(); }
        const result = await request(`/api/evidence/trace?bizNo=${encodeURIComponent(state.bizNo)}&afterId=${conversationCursor}&limit=100`);
        // rows 是留痕接口的确定字段（与 core/sdk.js 同口径）：缺失即契约漂移，不拿空数组冒充「无记录」。
        if (!Array.isArray(result.rows)) throw new Error('留痕接口未返回 rows 列表');
        const rows = result.rows;
        rows.forEach(appendConversation);
        if (rows.length) conversationCursor = Number(rows[rows.length - 1].id) || conversationCursor;
    }
    function appendConversation(row) {
        const key = row.eventId || row.id;
        if (conversationReceived.has(key)) return;
        const entry = rowToEntry(row);
        if (!entry) return;
        conversationReceived.add(key);
        conversation.value.push({ ...entry, id: key });
    }
    function startConversationSync(state) {
        clearInterval(conversationTimer);
        conversationTimer = null;
        loadConversation(state).catch(error => report('文字聊天记录拉取', error, { sticky: true }));
        if (state?.bizNo && state.processStatus !== 'COMPLETED') {
            conversationTimer = setInterval(() => loadConversation().catch(error => report('文字聊天记录拉取', error, { sticky: true })), 2000);
        }
    }
    async function sendConversation(text) {
        const state = business.value || callSession.value;
        if (!state?.bizNo) return toast('接听客户后才能发送消息', true);
        const sent = await request('/api/evidence/chat', {
            bizNo: state.bizNo, processId: state.processId, nodeName: state.nodeName,
            actorId: identity.agentId, actorRole: 'AGENT', text, clientTime: new Date().toISOString()
        });
        appendConversation(sent);
        await loadConversation();
    }

    /* ================= 首页数据 ================= */
    /**
     * 可办交易：契约 §4「GET /api/platform/trades」是唯一来源。
     * 曾经"接口不可用时回落到已注册场景清单"的经验性兜底已移除——回落会让后端故障
     * 以"页面一切正常"的假象呈现；现在失败如实带码上报，列表清空由首页
     * 「暂无可办交易」如实呈现（成功但目录为空同样显示为空，那也是后端事实）。
     */
    async function loadTrades() {
        try {
            const result = await request('/api/platform/trades');
            if (!Array.isArray(result.data)) throw new Error('可办交易接口未返回 data 列表');
            trades.value = result.data;
        } catch (error) {
            trades.value = [];
            report('可办交易加载', error);
        }
    }
    async function loadNotices() {
        try {
            const result = await request('/api/platform/notices');
            if (!Array.isArray(result.data)) throw new Error('系统公告接口未返回 data 列表');
            notices.value = result.data;
        } catch (error) {
            notices.value = [];
            // 失败呈现：卡片本身显示「公告服务暂不可用」，这里补上错误码与根因（console/system:error）。
            report('系统公告加载', error);
        }
    }
    const keyword = ref('');
    async function loadHistory(targetPage) {
        if (Number.isFinite(targetPage)) page.value = targetPage;
        try {
            // 服务端分页契约返回 total；旧接口没有 total，此时整体回落到 limit=100 + 本地切片。
            const result = historyLegacy.value
                ? await request(`/api/balance/history/list?keyword=${encodeURIComponent(keyword.value)}&limit=100`)
                : await request(`/api/balance/history/list?keyword=${encodeURIComponent(keyword.value)}&page=${page.value}&pageSize=${pageSize.value}`);
            if (!Array.isArray(result.data)) throw new Error('业务记录接口未返回 data 列表');
            history.value = result.data;
            if (!historyLegacy.value && !Number.isFinite(Number(result.total))) {
                historyLegacy.value = true;
                return loadHistory(targetPage);
            }
            historyTotal.value = historyLegacy.value ? history.value.length : Number(result.total);
            if (historyLegacy.value) page.value = 1;
        } catch (error) {
            bus.emit('history:error');   // 事件钩子保留；当前无监听者，呈现由下方 report 保证
            report('业务记录加载', error, { sticky: true });
        }
    }
    async function loadMetrics() {
        try {
            const response = await request('/api/balance/metrics/today');
            const data = response.data;
            // totalToday / completedToday 是后端当日指标的固定字段（与 business-svc 同口径）：
            // 缺失即契约漂移——如实抛出，不补 0、不猜别名掩盖。
            if (!data || data.totalToday == null || data.completedToday == null) {
                throw new Error('当日指标接口未返回 totalToday/completedToday 字段');
            }
            metricsToday.value = { total: data.totalToday, completed: data.completedToday };
        } catch (error) {
            metricsToday.value = { total: '—', completed: '—' };
            report('当日指标加载', error, { sticky: true });
        }
        await loadStats();
    }
    async function loadStats() {
        try {
            const response = await request(`/api/platform/agent/${encodeURIComponent(identity.agentId)}/stats/today`);
            const data = response.data;
            if (!data) throw new Error('坐席当日统计未返回 data 字段');
            const missing = ['signInSeconds', 'breakSeconds', 'serviceSeconds', 'averageServiceSeconds', 'servedCount', 'successCount']
                .filter(key => data[key] == null);
            if (missing.length) throw new Error(`坐席当日统计缺少字段：${missing.join('、')}`);
            stats.value = {
                signInSeconds: data.signInSeconds, breakSeconds: data.breakSeconds,
                serviceSeconds: data.serviceSeconds, averageServiceSeconds: data.averageServiceSeconds,
                servedCount: data.servedCount, successCount: data.successCount
            };
        } catch (error) {
            // 「保留上一次展示值」可以接受，但失败绝不能无痕：15s 轮询由 sticky 去重。
            report('坐席当日统计加载', error, { sticky: true });
        }
    }

    /* ================= 初始化 ================= */
    async function openHistoryDetail(bizNo, mode = 'summary') {
        bus.emit('history:open', { bizNo, mode });
    }

    async function init() {
        const profile = await request('/api/platform/agent-session');
        setAgentIdentity({ ...profile, agentName: profile.name, agentSkills: profile.skills });
        video.setHangupHandler(hangup);
        bus.on('agent:state', applyState);
        bus.on('agent:connection', status => { connection.value = status; });

        // 顶栏通话态跟随控件状态（§2.9.3 on('state') 订阅）：远端流到达/断开成对切换，
        // 与接听链路里的显式 setHeaderState('video') 互为兜底，避免通话断了还停在「视频中」。
        offVideoState = video.on('state', snapshot => {
            if (snapshot.call && callSession.value?.status === 'OPEN') setHeaderState('video');
            else if (!snapshot.call && headerState.value === 'video') setHeaderState(online.value ? 'online' : 'offline');
        });

        realtime.connect({ agentId: identity.agentId, name: identity.agentName, peerId: identity.peerId, skills: identity.agentSkills })
            .on('CALL_ENDED', event => { void onCallEnded(event).catch(error => report('通话结束清理', error)); })
            .on('ROOM_PARTICIPANTS_CHANGED', event => { if(event.callId===callSession.value?.callId)void video.updateParticipants(event.participants).catch(error=>report('房间成员更新',error)); })
            .on('PARTICIPANT_RELEASED', event => { if(event.callId===callSession.value?.callId && event.actorId===identity.agentId){void (async()=>{await sealRecording();await video.unmount();callSession.value=null;setHeaderState(online.value?'online':'offline');})().catch(error=>report('退出授权视频',error));} })
            .on('CHAT_MESSAGE', event => {
                if (event.processId === (business.value || callSession.value)?.processId) appendConversation(event);
            })
            .on('CALL_RINGING', detail => showRing(detail?.ticket))
            .on('CALL_ANSWERED', detail => {
                const processId = detail?.processId || detail?.ticket?.processId;
                // 仅本坐席接听当前来电时收起。老消息没有 agentId 时，同样必须
                // 携带当前 processId 才能影响横条。
                if (processId === ringing.value?.processId && (!detail?.agentId || detail.agentId === identity.agentId)) {
                    hideRing(null, processId);
                }
                void refreshQueue();
            })
            .on('QUEUE_UPDATED', () => { void refreshQueue(); })
            .on('QUEUE_SYNC', () => { void refreshQueue(); })
            // 流程节点推进：坐席端必须消费它，否则客户推进节点后本端界面永远停在旧节点。
            // 真实故障：客户提交签名、流程已到「人工核实签名」，坐席界面却仍显示「人工授权」——
            // 因为在线时轮询被完全停掉（sync.onRealtime('CONNECTED') → stopPolling），
            // 推送是唯一的状态来源，漏订阅等于没有任何兜底。
            .on('NODE_STATE', detail => {
                const frame = detail?.detail || detail;
                const processId = frame?.processId;
                if (processId && processId !== business.value?.processId) return;
                router.accept(frame, false, false);
            })
            // AGENT_RELEASED（改造方案 §14.1）：保留坐席的最后人工阶段完成后，服务端
            // 只发这一次释放事件，此后不再向坐席推 NODE_STATE。坐席据此收尾回可接待。
            .on('AGENT_RELEASED', detail => {
                const processId = (detail?.detail || detail)?.processId;
                if (processId && processId !== business.value?.processId) return;
                void releaseAfterAgentWork();
            })
            .on('CONNECTED', () => sync.onRealtime('CONNECTED'))
            .on('DISCONNECTED', () => sync.onRealtime('DISCONNECTED'));

        await Promise.all([loadTrades(), loadNotices(), loadHistory(), loadMetrics()]);
        queueTimer = setInterval(() => { void refreshQueue(); }, 2500);
        metricsTimer = setInterval(() => { void loadMetrics(); }, 15000);

        let savedOnline = 'false';
        try { savedOnline = sessionStorage.getItem(ONLINE_SESSION_KEY) || 'false'; }
        catch (error) { report('会话状态读取', error, { sticky: true }); }
        if (savedOnline === 'true') {
            // 恢复签入失败必须如实上报（带码），但不阻断后续业务恢复：
            // 本地保持未签入，坐席重新点一次「签入」即可，与服务端状态一致。
            try { await setOnline(true); }
            catch (error) { report('会话签入状态恢复', error, { sticky: true }); }
        }

        let saved = '';
        try { saved = sessionStorage.getItem(PROCESS_SESSION_KEY) || ''; }
        catch (error) { report('会话业务号读取', error, { sticky: true }); }
        if (saved) {
            try {
                const state = await flowApi.state(saved);
                await bindBusiness(state);
            } catch (error) {
                try { sessionStorage.removeItem(PROCESS_SESSION_KEY); }
                catch (cleanupError) { report('会话业务号清理', cleanupError, { sticky: true }); }
                // 恢复失败：给出人话指引并带码上报（不再只弹一句无码提示）。
                error.message = `上次业务暂时无法恢复，请从记录中查询：${error.message}`;
                report('上次业务恢复', error, { sticky: true });
            }
        }
    }

    function dispose() {
        [ringTimer, headerTimer, queueTimer, metricsTimer, conversationTimer].forEach(timer => clearInterval(timer));
        if (offVideoState) { offVideoState(); offVideoState = null; }
        realtime.disconnect();
    }

    return {
        view, online, headerState, queue, ringing, business, callSession, businessTabVisible, connection,
        trades, notices, history, historyTotal, historyLegacy, page, pageSize, keyword, metricsToday, stats, chat, conversation,
        agentNumber, queueCandidates, hasActiveRing, queuePopVisible, floatWaitVisible, businessTitle, steps,
        setHeaderState, leftPanel, signIn, signOut, setOnline, refreshQueue, toggleQueuePop,
        showRing, hideRing, rejectCall, answerCall, bindBusiness, startTrade, finish, release, hangup, logout,
        home, gotoBusiness, sendConversation, loadConversation, loadTrades, loadNotices, loadHistory, loadMetrics, loadStats,
        openHistoryDetail, applyState, init, dispose, toast, pushChat, setConnection, trace, flowState
    };
});
