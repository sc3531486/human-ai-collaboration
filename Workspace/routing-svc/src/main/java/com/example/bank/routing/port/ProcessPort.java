package com.example.bank.routing.port;

/**
 * 出站端口：读取流程节点状态，并在接听时认领业务会话。
 *
 * <p>由业务中枢实现。路由需要会话信息只为一件事 —— 判断工单绑定的接待节点是否还活着，
 * 从而在业务离开该节点时自动收口工单、释放接待资源。</p>
 *
 * <p><b>本端口只有"读"与"认领"两个动作，没有通用的写变量。</b>原有一个 {@code setVariables}
 * 供数字人命中后回写 {@code digital_human_id}，已删除：那是调用方正处流程事务中的跨服务回写，
 * 会在另一个事务里更新同一执行实例，调用方随后 flush 时版本已过期而整笔回滚；
 * 且该变量的唯一消费方就是这次回写本身（交还数字人时读回去当"优先复用"的依据）。
 * 接待方归属由工单自己维护，见 {@code QueueTicket#lastDigitalHumanId}。</p>
 */
public interface ProcessPort {
    java.util.Map<String,Object> variables(String processId);

    /** 流程实例仍在运行且当前停留在指定节点。实例已结束时返回 false，不视为异常。 */
    boolean isNodeActive(String processId, String activityId);

    /**
     * 业务中枢执行接听组合动作（认领、建房、邀请）。失败时路由不得提交接听状态。
     */
    void claimSession(String processId, String agentId, String peerId);
}
