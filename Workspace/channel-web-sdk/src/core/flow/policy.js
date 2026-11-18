/*
 * 节点与等待判据（契约 §3.6 的单一事实源）。
 *
 * 「人工视频节点」识别原先散落在 wait-tone.sync 与 instance.applyState 两处，
 * 且口径不一致（等待音认「节点名中文兜底」，接入不认）——同一概念两个事实源
 * 会导致等待音在播、视频却不接入的自相矛盾。统一收敛到本模块：改判据只改这里。
 */

/** 人工视频/见证节点：nodePolicy 任一字段命中，外加节点名中文兜底（防配置缺字段漏判）。 */
export function isHumanVideoNode(state) {
    const policy = (state && state.nodePolicy) || {};
    return Boolean(policy.video)
        || policy.communication === 'VIDEO'
        || policy.view === 'witness'
        || /人工授权|人工核实|座席视频/.test((state && state.nodeName) || '');
}

/** 人工节点开始等待；服务端 VIDEO_ROOM_READY 到达即结束，与摄像头画面到达时间无关。 */
export function isWaitingForAgent(state, roomInvited) {
    return (state && state.processStatus) === 'RUNNING'
        && isHumanVideoNode(state)
        && !roomInvited;
}

export default { isHumanVideoNode, isWaitingForAgent };
