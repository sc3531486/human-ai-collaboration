package com.example.bank.workflow.delegate;

import com.example.bank.workflow.contract.CustomerProfile;
import com.example.bank.workflow.contract.QueueRequest;
import org.flowable.engine.delegate.DelegateExecution;

/**
 * 从流程变量组装排队路由请求。
 *
 * <p>接待方类别与绑定节点全部来自目标节点的 {@code flowable:handling} 声明，本类不做节点名判断。</p>
 *
 * <p><b>画像直接来自流程变量，不回调 business-svc 查询档案。</b>
 * 进线建单时画像已写进流程变量，排队路由节点再查一次档案是多余的往返，
 * 也会让流程服务凭空多一个下游依赖 —— 而它本来只需要流程变量这一份数据。</p>
 */
final class QueueRequests {

    private QueueRequests() { }

    /** 目标节点策略转换为公共交接请求。 */
    static QueueRequest build(DelegateExecution execution, NodeHandlingPolicy policy,
                              com.example.bank.workflow.framework.FlowData data) {
        QueueRequest request=new QueueRequest();
        request.processId=execution.getProcessInstanceId();
        request.excludedAgentId=text(execution.getVariable("authorization_requester"));
        request.bizNo=text(execution.getVariable("biz_no"));
        request.transitionId=request.processId+":"+execution.getId()+":"+policy.nodeId+":"+text(execution.getVariable("submission_revision"));
        request.handlerType="DIGITAL".equals(policy.handler)?"DIGITAL_HUMAN":"HUMAN";
        request.handledNode=policy.nodeId;request.assignment=policy.assignment;
        request.profile=profile(execution);request.skillGroup=policy.skillGroup;
        request.bizType=text(execution.getVariable("biz_type"));
        request.amount=number(data.value(execution,"amount"));request.payee=text(data.value(execution,"payee"));
        request.mobilePeerId=text(execution.getVariable("mobile_peer_id"));request.channel=text(execution.getVariable("channel"));
        return request;
    }

    /**
     * 从流程变量还原画像；客户标识为空时返回 {@code null}，表示"这单没有客户档案"。
     *
     * <p>仅坐席主动受理允许没有渠道客户身份；渠道缺少身份会被调用方拒绝。</p>
     *
     * <p>字段全部来自 business-svc 写入的画像，缺失时不生成客户等级或渠道数据。</p>
     */
    static CustomerProfile profile(DelegateExecution execution) {
        String customerId = text(execution.getVariable("customer_id"));
        if (customerId.isEmpty()) return null;
        CustomerProfile profile = new CustomerProfile();
        profile.customerId = customerId;
        profile.name = text(execution.getVariable("customer_name"));
        profile.level = text(execution.getVariable("customer_level"));
        profile.levelName = text(execution.getVariable("customer_level_name"));
        profile.skillGroup = text(execution.getVariable("customer_skill_group"));
        profile.certNoMasked = text(execution.getVariable("customer_cert_masked"));
        profile.mobileMasked = text(execution.getVariable("customer_mobile_masked"));
        // 进线渠道与画像渠道同源：拆分后档案里的渠道不再可取，用进线渠道代替，语义一致
        profile.channel = text(execution.getVariable("channel"));
        // 风险名单由 business-svc 在建单时写入 customer_risk_listed；未写入时按未命中处理
        profile.riskListed = Boolean.parseBoolean(text(execution.getVariable("customer_risk_listed")));
        return profile;
    }

    static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static Double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }
}
