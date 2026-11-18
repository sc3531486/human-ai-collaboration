package com.example.bank.business.port;

import com.example.bank.business.contract.QueueTicket;

import java.util.List;

/**
 * 出站端口：排队工单。
 *
 * <p>由排队路由服务实现。工单是本服务的<b>只读视图</b> ——
 * 唯一副本在路由服务，这里不缓存、不回写。</p>
 *
 * <p>与 {@link AgentPort} 分开：那里是"谁能接"，这里是"这单给谁了"。
 * 两者的失败语义不同 —— 查不到工单是正常路径（风险校验通过、业务直通核心系统），
 * 查不到坐席却是异常。</p>
 */
public interface RoutingPort {

    QueueTicket route(java.util.Map<String,Object> request);

    /** 按流程实例取工单。不存在时返回 {@code null}：直通业务本就没有工单，不是错误。 */
    QueueTicket byProcess(String processId);

    /** 等待队列快照。{@code skillGroup} 为空表示不过滤技能组。 */
    List<QueueTicket> waiting(String skillGroup);

    /**
     * 坐席接听。业务已被他人接听或已离开人工节点时抛异常。
     *
     * @return 接听动作完成<b>那一刻</b>的工单。调用方拿它更新本地队列，
     *         而不是事后再查一次 —— 两次调用之间工单可能已被别的动作改动
     */
    QueueTicket answer(String agentId, String processId, String peerId);

    /**
     * 坐席拒接：坐席显式放弃，交给其他坐席继续匹配。
     *
     * @return 拒接后的工单（已回到等待队列）。理由同 {@link #answer}
     */
    QueueTicket reject(String agentId, String processId);

    /** 结束工单并释放接待方。幂等。 */
    void complete(String processId);
}
