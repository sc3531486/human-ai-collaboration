/*
 * 客户节点业务弹框（源：mobile_balance.html showMobileForm，契约 §2.1 / §3.6）。
 *
 * 解析与可见性规则（顺序与 navis 同口径，仅第 2 步按契约 §2.1-5 扩展为场景/未配置视图）：
 *   1) 去重键 = [processId,nodeId,revision,agentPeerId,action,fields]；只有弹框确已打开
 *      且确已渲染内容时才跳过（proposed 有内容一律重渲染）。
 *   2) 无 nodePolicy → 场景页接管；场景也没有 → 「当前节点尚未配置界面」+ 重新匹配（§2.1-5）。
 *   3) COMPLETED → 办结回执（无关闭入口，办结由 Flowable 推送，无"确认办结"按钮）。
 *   4) 场景命中 → 场景页；场景页拒绝（返回 false）→ 通用兜底。
 *   5) GenericNode：非客户节点或无字段 → 隐藏弹框；否则渲染可见。
 * 弹框在节点完成前不提供关闭入口。
 */
import { escape } from '../core/format.js';
import { router, resolveStage } from '../core/flow/router.js';
import VbSteps from '../ui/VbSteps.js';
import { receiptRows } from '../ui/receipt.js';

export function createNodeDialog(refs, options = {}) {
    const getPeerId = options.getPeerId || (() => '');
    const primeAudio = options.primeAudio || (() => {});
    const photoUpload = options.photoUpload;
    const authorizeCapture = options.authorizeCapture;
    const captureFrame = options.captureFrame;
    const submitFlow = options.submit;            // async (state, payload, action) => latestState
    const onApplied = options.onApplied || (() => {});
    const rematch = options.rematch || (() => {});

    let panelKey = '';
    let generation = 0;
    let release = null;
    let completionTimer = null;
    let dismissedCompletionKey = '';
    let activeCompletionKey = '';

    function clearCompletionTimer() {
        if (completionTimer) window.clearTimeout(completionTimer);
        completionTimer = null;
    }

    function closeCompleted(key) {
        dismissedCompletionKey = key || activeCompletionKey;
        activeCompletionKey = '';
        clearCompletionTimer();
        setVisible(false);
    }

    const setVisible = (visible, title = '业务信息', closable = false) => {
        refs.panel.hidden = !visible;
        if (refs.dialog && refs.dialog !== refs.panel) refs.dialog.hidden = !visible;
        if (refs.dialogTitle) refs.dialogTitle.textContent = title;
        if (refs.dialogClose) refs.dialogClose.hidden = !visible || !closable;
        // 弹框打开时，底层页面仍应保持原状作为上下文，不能把它切成空白确认页。
        refs.phone?.classList.remove('vb-confirming');
    };

    refs.dialogClose?.addEventListener('click', () => closeCompleted());

    function clearPage() {
        if (typeof release === 'function') {
            try {
                release();
            } catch (error) {
                console.warn('[node-dialog] 页面清理异常:', error);
            }
        }
        release = null;
    }

    function renderError(message) {
        clearPage();
        refs.panel.innerHTML = `<article class="node-form-body"><h3>业务页面加载失败</h3><p>${escape(message)}</p><button class="node-primary" type="button">重新加载</button></article>`;
        const button = refs.panel.querySelector('button');
        if (button) button.onclick = () => rematch();
        setVisible(true, '业务信息');
    }

    /**
     * 动态页面模块在网络较慢时才会解析完成。先把面板本身显式打开并给出内容，
     * 不能让用户看到“当前业务办理 + 节点名”却没有任何表单的空白弹框。
     */
    function renderLoading(title) {
        clearPage();
        refs.panel.hidden = false;
        refs.panel.innerHTML = `<article class="vb-node-form-body vb-node-dialog-loading" aria-live="polite"><span class="vb-node-loading-dot" aria-hidden="true"></span><h3>正在加载办理页面</h3><p>正在读取当前节点的业务要素，请稍候。</p></article>`;
        setVisible(true, title || '业务信息');
    }

    function mountUnmatched(state, ctx, token) {
        router.UnmatchedNode().then((page) => {
            if (token !== generation) return;
            clearPage();
            const outcome = page.mount(refs.panel, state, ctx);
            release = typeof outcome === 'function' ? outcome : null;
            setVisible(true, '当前节点尚未配置界面');
        }).catch(() => renderError('请点击下方按钮重新加载当前办理页面。'));
    }

    /** 页面装载：module 归一 → mount → 可见性。declined ＝ 页面声明不接管。 */
    async function mountPage(stageKind, state, policy, ctx, token) {
        const loader = stageKind === 'unmatched' ? router.UnmatchedNode : router.resolve(state);
        const page = await loader();
        if (token !== generation) return;
        clearPage();
        const outcome = await page.mount(refs.panel, state, ctx);
        if (token !== generation) return;
        if (outcome === false) return false;
        release = typeof outcome === 'function' ? outcome : null;
        const title = stageKind === 'unmatched'
            ? '当前节点尚未配置界面'
            : (policy && policy.title) || state.nodeName || '业务信息';
        setVisible(true, title);
        return true;
    }

    async function showMobileForm(state, proposed = {}) {
        const policy = state?.nodePolicy;
        VbSteps.render(refs.stepper, state);

        // 去重键与跳过条件（与 navis 逐条同口径）。
        const key = [state.processId, state.nodeId, state.revision, state.agentPeerId,
            policy?.action || '', (policy?.fields || []).map((field) => field.name).join(',')].join(':');
        if (key === panelKey
            && !Object.keys(proposed).length
            && !refs.panel.hidden
            && refs.panel.innerHTML.trim()
            && (!refs.dialog || refs.dialog === refs.panel || !refs.dialog.hidden)) return;
        // 重复推送不能作废尚在加载的同一页面；只在真正切换/重绘时更新代数。
        const token = ++generation;
        panelKey = key;

        const values = { ...(state.vars || {}), ...(state.payload || {}), ...proposed };
        const ctx = {
            get peerId() { return getPeerId(); },
            readOnly: Boolean(state.readOnly),
            values,
            primeAudio,
            authorizeCapture,
            photoUpload: (fieldName, blob) => photoUpload(state, fieldName, blob),
            captureFrame: () => captureFrame(),
            submit: async (payload, action) => {
                const latest = await submitFlow(state, payload, action);
                await onApplied(latest);
                return latest;
            },
            rematch,
        };

        try {
            // CUSTOMER 节点先打开带加载说明的容器。此处不依赖场景模块是否已下载，
            // 也避免第二次状态推送中断首次渲染后留下空白面板。
            const actors = Array.isArray(policy?.actors)
                ? policy.actors
                : String(policy?.actors || '').split(',').map((item) => item.trim());
            if (actors.includes('CUSTOMER')) {
                renderLoading(policy.title || state.nodeName || '业务信息');
            }

            // 2) 无 nodePolicy：场景页可接管，否则「尚未配置界面」（§2.1-5）。
            if (!policy) {
                const mounted = await mountPage(resolveStage(state) === 'unmatched' ? 'unmatched' : 'scenario', state, null, ctx, token);
                if (token !== generation) return;
                if (!mounted) mountUnmatched(state, ctx, token);
                return;
            }

            // 3) 办结回执：只展示，不提供关闭与确认入口（契约 §3.6）。
            if (state.processStatus === 'COMPLETED') {
                const completionKey = [state.processId, state.revision, state.nodeId].join(':');
                if (dismissedCompletionKey === completionKey) return;
                activeCompletionKey = completionKey;
                clearPage();
                refs.panel.innerHTML = `<article class="node-form-body vb-completion-receipt">${receiptRows(policy.displayFields, values)}</article>`;
                setVisible(true, '办理结果', true);
                clearCompletionTimer();
                completionTimer = window.setTimeout(() => closeCompleted(completionKey), 30000);
                return;
            }

            // 4) 场景页（可选美化层），声明不接管时回落通用兜底。
            const stage = resolveStage(state);
            if (stage === 'scenario') {
                const mounted = await mountPage('scenario', state, policy, ctx, token);
                if (token !== generation) return;
                if (mounted) return;
            }

            // 5) 通用兜底：纯 nodePolicy 驱动；不接管（非客户节点/无字段）则隐藏弹框。
            const mounted = await mountPage('generic', state, policy, ctx, token);
            if (token !== generation) return;
            if (!mounted) {
                clearPage();
                // CUSTOMER 节点若未收到字段配置，不能静默收起造成空白业务页。
                if (actors.includes('CUSTOMER')) {
                    refs.panel.innerHTML = '<article class="vb-node-form-body"><h3>业务页面暂未准备完成</h3><p>当前节点未下发可办理的表单字段，请重新读取流程状态。</p><button class="vb-node-primary" type="button">重新读取</button></article>';
                    refs.panel.querySelector('button').onclick = () => rematch();
                    setVisible(true, policy.title || state.nodeName || '业务信息');
                } else {
                    refs.panel.innerHTML = '';
                    setVisible(false);
                }
            }
        } catch (error) {
            if (token !== generation) return;
            console.error('打开业务表单失败', error);
            renderError('请点击下方按钮重新加载当前办理页面。');
        }
    }

    return {
        showMobileForm,
        setVisible,
        /** 切换节点时收起进行中的页面内容。 */
        reset() {
            generation += 1;
            clearPage();
            clearCompletionTimer();
            panelKey = '';
            dismissedCompletionKey = '';
            activeCompletionKey = '';
            refs.panel.innerHTML = '';
            setVisible(false);
        },
    };
}

export default createNodeDialog;
