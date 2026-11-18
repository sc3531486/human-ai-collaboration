package com.example.bank.business.contract;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 排队工单的跨服务镜像。
 *
 * <p><b>字段名与默认值必须与 routing-svc 的 {@code QueueTicket} 逐字一致。</b>
 * 反序列化时 JSON 里的 {@code null} 会覆盖本类的默认值，因此"字段缺失"与"字段为 null"
 * 在 HTTP 边界上不等价 —— 这里少一个默认值，返回给页面的等级就会显示成 null。</p>
 *
 * <p>这份镜像跟随对端全字段，不做裁剪：工单原样透出给浏览器，
 * 裁剪会改变对外接口契约。</p>
 */
public class QueueTicket {
    public String ticketId;
    public String processId;
    public String bizNo;
    /** 产品化业务关联键；等于 bizNo，产品只存不解释。 */
    public String bizKey;
    /** 业务日期（yyyy-MM-dd），由 business 传入。 */
    public String bizDate;
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
    /** 当前接待方类别，取值见 {@link HandlerType}。 */
    public String handlerType = HandlerType.HUMAN;
    /** 当前接待方标识：人工坐席号或数字人编号。 */
    public String agentId;
    /** 本工单绑定的流程节点；流程离开该节点即自动收口。 */
    public String handledNode;
    public String preferredHandlerId;
    public long enqueuedAt = System.currentTimeMillis();
    public long ringStartedAt;
    public long updatedAt = System.currentTimeMillis();
    public Set<String> rejectedBy = new LinkedHashSet<String>();
}
