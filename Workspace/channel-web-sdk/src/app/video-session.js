/*
 * 控件会话状态机（契约 §2.9 / §3.6）——app 层唯一组织「接入/释放/挂起续接」的地方。
 *
 * 原先 engage/release/挂起/握手标志散在 instance.js 编排层里，录像与状态应用都
 * 直接摸控件命令；抽出后 instance 只做装配，业务层（takeover/applyState）只面对
 * 本模块的 engage/release/engaged/connected 四个语义。
 *
 * 换音视频厂商的扩展点：业务层不感知控件命令与状态 schema——命令面与快照字段
 * 全部收敛在 core/video-widget.js 这一个接缝里，本模块只消费其语义化接口。
 *
 * 红线 §3.6：同意告知书之前 engage() 只登记挂起请求、不取任何媒体；
 * 同意手势由 consent.onAccepted 调 resume() 续接。
 */
import { video } from '../core/video-widget.js';

export function createVideoSession(refs, options = {}) {
    const consent = options.consent || { consented: false };
    const media = options.media;
    const getPeerId = options.getPeerId || (() => '');
    const getLocalPeerId = options.getLocalPeerId || (() => '');
    const getProcessId = options.getProcessId || (() => '');
    const isDisposed = options.isDisposed || (() => false);

    let stage = null;
    let pending = null;   // 未同意时挂起的接入请求
    let engaged = false;  // 握手已发起（成功后置位；失败/未接入保持 false 允许重试）

    /** 服务端尚未分配人工前预注册本端 Peer，接听时不再出现 unavailable-id。 */
    async function prepare() {
        if (isDisposed()) return false;
        try {
            return await video.prepare({ localPeerId: getLocalPeerId(), processId: getProcessId() });
        } catch (error) {
            console.warn('[video-session] 视频信令端点预注册失败:', error);
            return false;
        }
    }

    /** 挂载 → open → connect；未同意告知书时挂起（返回 false，同意后 resume 续接）。 */
    async function engage(opts = {}) {
        if (isDisposed()) return false;
        if (!consent.consented) {
            pending = opts;   // 同意后由 consent.onAccepted 续接，不在手势外取媒体
            return false;
        }
        try {
            if (!stage) {
                stage = await video.mountInto(refs.widgetSlot, {
                    toolbar: false,          // 宿主自建控件条，避免双份操作区
                    showLocalPreview: true,
                    zIndex: 100,
                    placeholder: '等待坐席视频接入…',
                });
                if (stage) {
                    refs.widgetSlot.style.display = 'block';
                    refs.widgetActive = true;
                    if (refs.pip) refs.pip.style.display = 'none';
                }
            }
            if (!stage) return false;   // 控件未接入（未配置/加载失败）：走无视频路径
            await video.open({ mode: 'overlay', draggable: true,
                layout: { left: 0.56, top: 0.09, width: 0.4, height: 0.22 } });
            let localStream = null;
            try {
                localStream = await media.ensureLocalStream();   // 同意后的 SDK 本地流，避免控件二次取权
            } catch (error) {
                // 取流失败不静默：如实告警后仍把建链交出去——控件可在已授权环境自取媒体，
                // 再失败由本函数外层 catch 收口为 false，业务按 §2.9.3「无视频办理到底」。
                console.warn('[video-session] SDK 本地流获取失败，交由控件自取:', error);
            }
            await video.connect({
                agentPeerId: getPeerId(),
                localPeerId: getLocalPeerId(),
                processId: getProcessId(),
                localStream: localStream || undefined,
                desktop: false,              // 渠道端不采集桌面
                incomingOnly: opts.incomingOnly === true,
            });
            if (opts.audioOnly) await video.takeover('audio');
            engaged = true;
            pending = null;
            return true;
        } catch (error) {
            // 业务层收口（不是接缝）：接入失败不阻断办理——按 §2.9.3 转无视频路径，且必须可见。
            console.warn('[video-session] 控件接入未就绪，转无视频路径继续办理:', error);
            return false;
        }
    }

    /**
     * 拆卸期尽力而为：清理命令（close/takeover/unmount）被拒时如实告警——
     * 既不产生未处理拒绝，也不把异常吞成静默成功；拆卸流程必须走完。
     */
    function bestEffort(fn, label) {
        try {
            Promise.resolve(fn()).catch((error) => console.warn(`[video-session] ${label}失败:`, error));
        } catch (error) {
            console.warn(`[video-session] ${label}失败:`, error);
        }
    }

    /** 释放：清挂起 → 关命令面 → 回 released 渲染 → 卸 stage → 隐藏槽位。 */
    function release() {
        pending = null;
        bestEffort(() => video.close(), '关闭控件命令面');
        if (stage) {
            bestEffort(() => video.takeover('released'), '回落 released 渲染');
            bestEffort(() => video.unmount(stage), '卸载控件 stage');
            stage = null;
        }
        refs.widgetSlot.style.display = 'none';
        refs.widgetActive = false;
        engaged = false;
    }

    /** 同意手势后续接挂起的接入（由 consent.onAccepted 调用）。 */
    function resume() {
        if (!pending) return;
        const opts = pending;
        pending = null;
        bestEffort(() => engage(opts), '同意后续接视频接入');
    }

    return {
        engage,
        prepare,
        release,
        resume,
        /** 连通判据（navis videoConnected 口径）；快照字段 schema 收敛在接缝层。 */
        connected: () => video.isConnected(),
        get engaged() { return engaged; },
        get hasStage() { return Boolean(stage); },
    };
}

export default createVideoSession;
