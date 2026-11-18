package com.example.bank.workflow.delegate;

import com.example.bank.workflow.contract.QueueRequest;
import com.example.bank.workflow.framework.FlowData;
import com.example.bank.workflow.session.BusinessSessionService;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务节点进入动作（公共能力）：流程推进到等待节点时按<b>该节点</b>的办理策略执行接待交接。
 *
 * <p>与旧接待节点的差别：</p>
 * <ul>
 *   <li>排队、交接不再是独立业务节点：挂在等待节点的进入路径上（serviceTask），
 *       目标等待节点用 {@code flowable:handling} 声明办理策略（{@link NodeHandlingPolicy}），
 *       本动作只声明资源收尾开关（{@link NodeTransitionConfig}）；</li>
 *   <li>交接只管"接待"一件事：办理方切换与办理分段由 business-svc 统一处理，
 *       坐席保留与通话生命周期按策略交给公共交接协议执行，本类不直接改写会话归属；</li>
 *   <li>坐席退出（{@code agent_work_complete}）只发生在交接动作显式声明
 *       {@code releaseAgent=true} 时，即坐席完成最后一个人工节点 —— 退出坐席界面不影响流程，
 *       后续节点全部由数字人与后台继续处理。</li>
 * </ul>
 *
 * <p>本类不直接访问 routing-svc 或 media-svc。排队算法、资源占用、媒体房间生命周期
 * 由 business-svc 统一编排，再分别调用对应服务。</p>
 */
@Component("nodeTransitionDelegate")
public class NodeTransitionDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(NodeTransitionDelegate.class);

    private final RestTemplate http;
    private final String businessBaseUrl;
    private final BusinessSessionService sessions;
    private final FlowData data;

    public NodeTransitionDelegate(RestTemplate http,
                                  @Value("${workflow.business-base-url:http://localhost:8080}") String businessBaseUrl,
                                  BusinessSessionService sessions,
                                  FlowData data) {
        this.http = http;
        this.businessBaseUrl = businessBaseUrl;
        this.sessions = sessions;
        this.data = data;
    }

    @Override
    public void execute(DelegateExecution execution) {
        NodeTransitionConfig config = NodeTransitionConfig.read(
                execution.getCurrentFlowElement() instanceof org.flowable.bpmn.model.FlowElement
                        ? (org.flowable.bpmn.model.FlowElement) execution.getCurrentFlowElement() : null);
        NodeHandlingPolicy policy = targetPolicy(execution, config);

        QueueRequest request = QueueRequests.build(execution, policy, data);

        // 交接编号进入流程变量：坐席提交时用它把真实 taskId/visitId 绑定到已受理的意图上
        execution.setVariable("transition_node_" + policy.nodeId, request.transitionId);

        // 释放人工归属必须在流程事务内本地完成（CONTRACTS §10 规则 1/3）：
        // business-svc 在受理交接时正被本事务调用，不允许它回写同一流程实例。
        // 先释放再受理：受理回执与到 routing 的请求里看到的都是已释放后的状态。
        if (config.releaseAgent) {
            sessions.release(execution.getProcessInstanceId());
            // 坐席完成最后人工节点后的退出信号：坐席端封存录像、挂断并退出交易页。
            execution.setVariable("agent_work_complete", true);
        }
        if (policy.retainsAgent()) {
            execution.setVariable("agent_work_complete", false);
        }
        // 坐席主动受理没有渠道客户接待资源；渠道进线缺客户身份属于数据错误，不跳过路由。
        if (QueueRequests.profile(execution) == null) {
            if ("AGENT".equals(execution.getVariable("entry_type"))) return;
            throw new IllegalStateException("渠道流程缺少业务服务提供的 customer_id");
        }
        Map<String, Object> response = postTransition(request, policy, config);
        if (response != null && response.get("segmentIndex") instanceof Number) {
            execution.setVariable("evidence_segment_index", response.get("segmentIndex"));
        }
    }

    /**
     * 业务受理失败时节点进入动作整体失败，流程停在前一位置等待重试；
     * 不伪造交接成功，也不跳过人工节点。
     */
    private Map<String, Object> postTransition(QueueRequest request, NodeHandlingPolicy policy,
                                               NodeTransitionConfig config) {
        try {
            Map<String,Object> response = http.postForObject(businessBaseUrl + "/internal/reception/transition",
                    json(transitionBody(request, policy, config)), Map.class);
            if(response==null || !Boolean.TRUE.equals(response.get("accepted"))) {
                throw new IllegalStateException("业务服务未返回已受理的接待交接回执");
            }
            return response;
        } catch (RuntimeException error) {
            log.warn("接待交接未受理成功 transitionId={} node={} cause={}",
                    request.transitionId, request.handledNode, error.getMessage());
            throw error;
        }
    }

    Map<String, Object> transitionBody(QueueRequest request, NodeHandlingPolicy policy,
                                       NodeTransitionConfig config) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request", request);
        payload.put("releaseAgent", config.releaseAgent);
        payload.put("closeRoom", config.closeRoom);
        payload.put("retainAgent", policy.retainsAgent());
        payload.put("communication", policy.communication);
        return payload;
    }

    /**
     * 读取目标等待节点的办理策略：优先使用交接动作显式声明的 {@code targetNode}（定义了
     * 办理策略的节点或用作工单绑定的节点标识）；否则沿出边查找唯一带 handling 的等待节点。
     * 找不到或命中多个即配置错误，在产生任何接待副作用之前报错。
     */
    private NodeHandlingPolicy targetPolicy(DelegateExecution execution, NodeTransitionConfig config) {
        Object element = execution.getCurrentFlowElement();
        if (!(element instanceof FlowNode)) {
            throw new IllegalStateException("节点进入动作必须挂在流程节点上：" + execution.getCurrentActivityId());
        }
        FlowNode action = (FlowNode) element;
        NodeHandlingPolicy direct = NodeHandlingPolicy.read(action);
        if (direct != null) return direct;
        if (config.target != null) {
            org.flowable.bpmn.model.BpmnModel model = org.flowable.engine.impl.util.CommandContextUtil.getProcessEngineConfiguration()
                    .getRepositoryService().getBpmnModel(execution.getProcessDefinitionId());
            NodeHandlingPolicy declaredPolicy = NodeHandlingPolicy.read(model == null ? null : model.getFlowElement(config.target));
            if (declaredPolicy != null) return declaredPolicy;
            // 声明目标本身没有 handling（如系统执行节点）：按释放型交接处理，节点仅作工单绑定标识
            return NodeHandlingPolicy.synthetic(config.target);
        }
        NodeHandlingPolicy found = null;
        for (org.flowable.bpmn.model.SequenceFlow flow : action.getOutgoingFlows()) {
            NodeHandlingPolicy policy = NodeHandlingPolicy.read(flow.getTargetFlowElement());
            if (policy != null) {
                if (found != null) {
                    throw new IllegalStateException("一次交接只能指向一个配置办理策略的等待节点："
                            + execution.getCurrentActivityId());
                }
                found = policy;
            }
        }
        if (found == null) {
            throw new IllegalStateException("交接动作未指向配置办理策略的等待节点："
                    + execution.getCurrentActivityId());
        }
        return found;
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
