package com.example.bank.routing.routing;

import java.util.LinkedHashSet;
import java.util.Set;

/** 进线排队工单：从客户进线排队到坐席接听的完整状态载体。 */
public class QueueTicket {
    public String ticketId;
    public String processId;
    public String excludedAgentId;
    public String bizNo;

    /** 产品化业务关联键；缺省等于 bizNo/processId。 */
    public String bizKey;

    /** 业务日期（yyyy-MM-dd），路由只存不解释。 */
    public String bizDate;

    /** 队列ID；缺省 default。 */
    public String queueId = "default";

    /** 优先级。 */
    public int priority;
    public String customerId;
    public String customerName;
    public String level;
    public String levelName;
    public String skillGroup;
    public String channel;
    public String bizType;
    public Double amount;
    public String payee;
    public String mobilePeerId = "";
    public boolean riskListed;
    /** WAITING / RINGING / ANSWERED / REJECTED / CANCELLED / EXPIRED */
    public String status = "WAITING";
    /** 当前接待方类别，取值见 {@link HandlerType}：HUMAN / DIGITAL_HUMAN。 */
    public String handlerType = HandlerType.HUMAN;
    /**
     * 当前接待方标识：人工坐席号或数字人编号。
     * 字段名沿用 agentId 以兼容既有坐席端队列契约，新代码应按"接待方标识"理解。
     */
    public String agentId;
    /**
     * 本工单绑定的流程节点。工单在流程离开该节点时自动收口并释放接待方，
     * 因此人工接待为 human_auth、数字人接待为 ai_collect、交还后为 ai_broadcast。
     */
    public String handledNode;
    /**
     * 上一次接待本会话的数字人编号，供交还时优先复用（避免中途换人让客户重建信任）。
     *
     * <p>必须由工单自己记住：改派会把 {@link #agentId} 清空（见 {@code route}），
     * 接待方历史在这一步就丢了。原先由流程变量 {@code digital_human_id} 承载，
     * 代价是本服务要把编号回写进调用方正处事务中的流程实例 —— 与 {@code handler_type}
     * 被删时是同一个病，且那个变量的唯一消费方就是这次回写本身。</p>
     */
    public String lastDigitalHumanId;
    /** 期望复用的接待方标识；匹配时优先命中，不可用则退回技能组匹配。 */
    public String preferredHandlerId;
    public long enqueuedAt = System.currentTimeMillis();
    public long ringStartedAt;
    public long answeredAt;
    public long completedAt;
    public long expiresAt;
    public long updatedAt = System.currentTimeMillis();
    public Set<String> rejectedBy = new LinkedHashSet<String>();
}
