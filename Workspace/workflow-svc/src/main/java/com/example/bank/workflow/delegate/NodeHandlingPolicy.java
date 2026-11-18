package com.example.bank.workflow.delegate;

import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.bpmn.model.UserTask;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 业务节点办理策略：从<b>目标等待节点</b>的 {@code flowable:handling} 扩展读取。
 *
 * <p>改造后节点策略与流程定义共同部署、共同版本化；实例按启动时的 BPMN 版本读取，
 * 不存在数据库里的第二份运行配置。等待节点缺失策略即视为配置错误，
 * 在产生任何接待副作用之前报错（不兜底旧节点名）。</p>
 *
 * <p>策略字段与改造方案 §4.2 一致：</p>
 * <ul>
 *   <li>{@code handler}：办理主体 {@code DIGITAL} / {@code HUMAN}（接待主体，不等于业务接口执行方）</li>
 *   <li>{@code submitter}：允许推进节点的参与方 {@code CUSTOMER} / {@code AGENT} / {@code SYSTEM}</li>
 *   <li>{@code assignment}：人工资源分配 {@code NONE} / {@code NEW} / {@code REUSE}；
 *       REUSE 表示优先绑定仍在保留期内的原坐席，仅人工节点可配</li>
 *   <li>{@code skillGroup}：人工节点的技能要求（缺省 GENERAL）</li>
 *   <li>{@code retainAgent}：离开当前节点后是否保留坐席资源，与当前办理方独立（声明性元数据，
 *       实际释放由交接动作按其自身配置执行）</li>
 *   <li>{@code communication}：{@code NONE} / {@code AUDIO} / {@code VIDEO} / {@code KEEP}；
 *       保留坐席不必等同于保持视频</li>
 *   <li>{@code mobilePage} / {@code agentPage}：两端页面标识，页面独立注册</li>
 * </ul>
 */
final class NodeHandlingPolicy {

    static final List<String> HANDLERS = Arrays.asList("DIGITAL", "HUMAN");
    static final List<String> SUBMITTERS = Arrays.asList("CUSTOMER", "AGENT", "SYSTEM");
    static final List<String> ASSIGNMENTS = Arrays.asList("NONE", "NEW", "REUSE");
    static final List<String> COMMUNICATIONS = Arrays.asList("NONE", "AUDIO", "VIDEO", "KEEP");

    final String handler;
    final String submitter;
    final String assignment;
    final String skillGroup;
    final boolean retainAgent;
    final String communication;
    final String mobilePage;
    final String agentPage;
    final String nodeId;

    private NodeHandlingPolicy(String nodeId, String handler, String submitter, String assignment,
                               String skillGroup, boolean retainAgent, String communication,
                               String mobilePage, String agentPage) {
        this.nodeId = nodeId;
        this.handler = handler;
        this.submitter = submitter;
        this.assignment = assignment;
        this.skillGroup = skillGroup;
        this.retainAgent = retainAgent;
        this.communication = communication;
        this.mobilePage = mobilePage;
        this.agentPage = agentPage;
    }

    /** 释放型交接的兜底策略：交接后无等待节点（直接进入系统执行），仅用于工单绑定与办理方登记。 */
    static NodeHandlingPolicy synthetic(String nodeId) {
        return new NodeHandlingPolicy(nodeId, "DIGITAL", "SYSTEM", "NONE", null, false, "NONE", null, null);
    }

    /** 从流程元素读取办理策略；没有 handling 配置的节点返回 {@code null}（调用方决定是否必须）。 */
    static NodeHandlingPolicy read(FlowElement element) {
        if (element == null) return null;
        Map<String, List<org.flowable.bpmn.model.ExtensionElement>> extensions = element.getExtensionElements();
        List<org.flowable.bpmn.model.ExtensionElement> entries = extensions == null
                ? null : extensions.getOrDefault("handling", null);
        if (entries == null || entries.isEmpty()) return null;
        if (entries.size() > 1) {
            throw new IllegalStateException("节点只能配置一组 handling：" + element.getId());
        }
        org.flowable.bpmn.model.ExtensionElement config = entries.get(0);
        String handler = attribute(config, "handler");
        String submitter = attribute(config, "submitter");
        String assignment = attribute(config, "assignment");
        String communication = attribute(config, "communication");
        if (!HANDLERS.contains(handler)) {
            throw new IllegalStateException("handling.handler 必须为 DIGITAL 或 HUMAN：" + element.getId());
        }
        if (!SUBMITTERS.contains(submitter)) {
            throw new IllegalStateException("handling.submitter 必须为 CUSTOMER、AGENT 或 SYSTEM：" + element.getId());
        }
        if (!ASSIGNMENTS.contains(assignment)) {
            throw new IllegalStateException("handling.assignment 必须为 NONE、NEW 或 REUSE：" + element.getId());
        }
        if (!COMMUNICATIONS.contains(communication)) {
            throw new IllegalStateException("handling.communication 必须为 NONE、AUDIO、VIDEO 或 KEEP：" + element.getId());
        }
        boolean human = "HUMAN".equals(handler);
        if (human && "NONE".equals(assignment)) {
            throw new IllegalStateException("人工节点必须声明资源分配方式 NEW 或 REUSE：" + element.getId());
        }
        if (!human && !"NONE".equals(assignment)) {
            throw new IllegalStateException("只有人工节点可以申请接待资源：" + element.getId());
        }
        if (human && "NONE".equals(communication)) {
            throw new IllegalStateException("人工节点必须声明通信模式，不能为 NONE：" + element.getId());
        }
        return new NodeHandlingPolicy(element.getId(), handler, submitter, assignment,
                attribute(config, "skillGroup"), bool(config, "retainAgent", element.getId()), communication,
                attribute(config, "mobilePage"), attribute(config, "agentPage"));
    }

    static boolean supports(FlowElement element) {
        return element instanceof UserTask || element instanceof ReceiveTask;
    }

    /** 人工节点通过 REUSE 表达保留坐席；REUSE 时路由优先绑定仍在保留期内的原坐席。 */
    boolean retainsAgent() {
        return "REUSE".equals(assignment);
    }

    private static String attribute(org.flowable.bpmn.model.ExtensionElement config, String name) {
        String value = config.getAttributeValue(null, name);
        if (value == null) value = config.getAttributeValue("", name);
        return value == null ? null : value.trim();
    }

    private static boolean bool(org.flowable.bpmn.model.ExtensionElement config, String name, String nodeId) {
        String value = attribute(config, name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("handling 必须显式声明 " + name + "：" + nodeId);
        }
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalStateException(name + " 必须显式配置 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }
}
