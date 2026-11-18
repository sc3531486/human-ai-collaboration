package com.example.bank.workflow.contract;

/**
 * 进线排队工单 —— 跨服务传输的<b>只读裁剪视图</b>。
 *
 * <p>工单的唯一副本在 routing-svc，本服务只用它回显日志与判断"派给了谁"，
 * 因此只镜像实际读取的字段。**不要在本服务缓存工单状态**：
 * 工单随接听、拒接、超时在路由侧不停变化，本地留副本必然与事实源互相打脸。</p>
 */
public class QueueTicket {
    public String ticketId;
    public String processId;
    public String bizNo;
    public String skillGroup;
    /**
     * 当前接待方标识：人工坐席号或数字人编号。
     * 字段名沿用 agentId 以兼容既有坐席端队列契约，新代码应按"接待方标识"理解。
     */
    public String agentId;
    /** WAITING / RINGING / ANSWERED / REJECTED / CANCELLED / EXPIRED */
    public String status;
}
