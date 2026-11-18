package com.example.bank.workflow.contract;

/**
 * 入队 / 改派请求：一次接待交接所需的全部要素。
 *
 * <p>同一通会话复用同一张工单，接待方在数字人与人工之间切换时以本对象重新排队。</p>
 *
 * <p>本类出站方向为 workflow-svc → routing-svc，是本服务<b>只写不读</b>的 DTO，
 * 因此字段默认值必须与 routing-svc 的 QueueRequest 保持一致（理由见
 * {@link CustomerProfile} 的类注释）。</p>
 */
public class QueueRequest {

    public String processId;
    public String excludedAgentId;
    public String bizNo;
    public String transitionId;
    public String assignment;

    /** 本次要路由到的接待方类别，取值见 {@link HandlerType}。 */
    public String handlerType;

    /** 本工单绑定的流程节点；流程离开该节点即自动收口并释放接待方。 */
    public String handledNode;

    // 原有一个 preferredHandlerId（"期望复用的接待方"），已删除。它的值来自流程变量
    // digital_human_id，而那个变量正是 routing-svc 在上一次数字人命中时回写进来的 ——
    // 等于把对方刚写下的事实再读回来还给他。规则已归位到 routing-svc 的工单
    // （QueueTicket#lastDigitalHumanId）：工单自己记得住上一次接待的数字人。
    // 连带收益：路由调用不再回写本流程实例，交还数字人节点不必再为此把调用推到提交之后。

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
