package com.example.bank.workflow.delegate;

import org.flowable.bpmn.model.FlowElement;

import java.util.List;
import java.util.Map;

/**
 * 节点进入动作配置：从交接 serviceTask 的 {@code flowable:transition} 扩展读取。
 *
 * <p>排队、交接不再是独立业务节点：挂在等待节点的进入路径上，
 * 目标等待节点用 {@code flowable:handling} 声明办理策略（{@link NodeHandlingPolicy}），
 * 本配置只声明这次交接的资源收尾动作：</p>
 * <ul>
 *   <li>{@code releaseAgent}：交接完成后是否释放人工坐席并触发坐席退出
 *       （{@code agent_work_complete}）。只在坐席完成最后人工节点时为 true ——
 *       "转数字人办理"不再天然等于释放坐席。</li>
 *   <li>{@code closeRoom}：交接完成后是否封存人工段录像并关闭视频房间；
 *       保留坐席不必等同于保持视频，两者独立声明。</li>
 * </ul>
 */
final class NodeTransitionConfig {

    final boolean releaseAgent;
    final boolean closeRoom;
    final String nodeId;
    /** 显式声明的办理策略节点：适用于交接后不指向等待节点（如直接进入系统执行节点）的场景。 */
    final String target;

    private NodeTransitionConfig(String nodeId, boolean releaseAgent, boolean closeRoom, String target) {
        this.nodeId = nodeId;
        this.releaseAgent = releaseAgent;
        this.closeRoom = closeRoom;
        this.target = target;
    }

    /** 交接动作必须显式声明收尾开关，缺失即在产生接待副作用之前报错。 */
    static NodeTransitionConfig read(FlowElement element) {
        if (element == null) {
            throw new IllegalStateException("节点进入动作缺少流程元素");
        }
        Map<String, List<org.flowable.bpmn.model.ExtensionElement>> extensions = element.getExtensionElements();
        List<org.flowable.bpmn.model.ExtensionElement> entries = extensions == null
                ? null : extensions.getOrDefault("transition", null);
        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException("交接动作缺少 transition 配置：" + element.getId());
        }
        if (entries.size() > 1) {
            throw new IllegalStateException("交接动作只能配置一组 transition：" + element.getId());
        }
        org.flowable.bpmn.model.ExtensionElement config = entries.get(0);
        boolean releaseAgent = bool(config, "releaseAgent", element.getId());
        boolean closeRoom = bool(config, "closeRoom", element.getId());
        if (!releaseAgent && closeRoom) {
            throw new IllegalStateException("关闭人工房间时必须同时释放坐席归属：" + element.getId());
        }
        String target = attr(config, "targetNode");
        if (target != null && !target.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
            throw new IllegalStateException("targetNode 必须是有效的节点 ID：" + element.getId());
        }
        return new NodeTransitionConfig(element.getId(), releaseAgent, closeRoom, target);
    }

    private static String attr(org.flowable.bpmn.model.ExtensionElement config, String name) {
        String value = config.getAttributeValue(null, name);
        if (value == null) value = config.getAttributeValue("", name);
        return value == null ? null : value.trim();
    }

    private static boolean bool(org.flowable.bpmn.model.ExtensionElement config, String name, String nodeId) {
        String value = attr(config, name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("交接动作必须显式声明 " + name + "：" + nodeId);
        }
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalStateException(name + " 必须显式配置 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }
}
