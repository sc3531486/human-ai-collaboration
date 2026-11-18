package com.example.bank.workflow.port;

import com.example.bank.workflow.contract.QueueRequest;
import com.example.bank.workflow.contract.QueueTicket;

/**
 * 排队路由端口 —— 由<b>本服务</b>定义，routing-svc 实现。
 *
 * <p>方向是反转的：本服务只声明"我要把这张工单排给某类接待方"，
 * 工单状态机、坐席名册、数字人资源池都是 routing-svc 的实现细节。</p>
 *
 * <p>本服务<b>不持有</b>坐席名册与工单表的任何副本：那两份状态的唯一副本在 routing-svc，
 * 在这里留一份必然出现两个进程各说各话。</p>
 */
public interface RoutingPort {

    /**
     * 重新排队路由一张工单：新建（首次进线）或改派（人工↔数字人互转）走同一入口。
     *
     * <p>对端不可达时<b>异常向上传播</b>，本方法不吞异常。排队是流程推进的必要环节：
     * 若在此吞掉失败，流程会继续走到人工节点，等一个永远不会被派单的坐席，
     * 而客户侧显示"进线成功" —— 比直接失败更难排查。抛出后流程事务回滚，进线失败可重试。</p>
     *
     * @return 对端返回的工单视图（本服务只读其中数项，见 {@link QueueTicket}）
     */
    QueueTicket route(QueueRequest request);

    /**
     * 记一次交易成功，供坐席端「当日交易成功数」统计。
     *
     * <p>与 {@link #route} 相反，这里<b>失败只记日志</b>：流程已经正常走完，这是既成事实，
     * 为了一个统计数字回滚已完成的交易是本末倒置。</p>
     */
    void recordSuccess(String agentId);
}
