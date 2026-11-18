package com.example.bank.routing.routing;

import com.example.bank.routing.profile.CustomerProfile;

/**
 * 入队 / 改派请求：一次接待交接所需的全部要素。
 * 同一通会话复用同一张工单，接待方在数字人与人工之间切换时以本对象重新排队。
 */
public class QueueRequest {

    public String processId;
    public String excludedAgentId;
    public String bizNo;

    /** 产品化业务关联键；缺省回落 bizNo/processId，旧调用方无需改。 */
    public String bizKey;

    /** 业务日期（yyyy-MM-dd），由调用方传入，路由只存不解释。 */
    public String bizDate;

    /** 队列ID；缺省 default。 */
    public String queueId;

    /** 优先级；缺省 0。 */
    public int priority;

    /** 本次要路由到的接待方类别，取值见 {@link HandlerType}。 */
    public String handlerType;

    /** 本工单绑定的流程节点；流程离开该节点即自动收口并释放接待方。 */
    public String handledNode;

    // 原有一个 preferredHandlerId（"期望复用的接待方"），已删除。调用方唯一填入它的地方是
    // 交还数字人节点，而那个值来自流程变量 digital_human_id —— 也就是本服务自己回写进去的。
    // 绕一圈等于本服务向调用方要回自己刚写下的东西，还附带一次跨服务回写。规则已归位到
    // QueueTicket#lastDigitalHumanId：工单记得住上一次接待的数字人，交还时自行优先复用。

    /** 客户画像，技能组取自 {@link CustomerProfile#skillGroup}。 */
    public CustomerProfile profile;

    /** 画像缺失时的技能组兜底，避免因档案问题放弃整条进线。 */
    public String skillGroup;

    public String bizType;
    public Double amount;
    public String payee = "";
    public String mobilePeerId = "";
    public String channel;
}
