package com.example.bank.workflow.framework;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 BPMN 模型读取节点元数据，替代 SceneRegistry。
 *
 * <p>节点的 actors 和 actionCode 通过 extension elements 存储在 BPMN 中，
 * 本类负责解析这些扩展属性。</p>
 */
@Component
public class BpmnMetaReader {

    private final RepositoryService repository;

    public BpmnMetaReader(RepositoryService repository) {
        this.repository = repository;
    }

    /**
     * 读取节点元数据。
     *
     * @param definitionId 流程定义 ID
     * @param nodeId 节点 ID
     * @return 节点元数据，包含 nodeName, nodeType, actors, actionCode
     */
    public Map<String, String> nodeMeta(String definitionId, String nodeId) {
        BpmnModel model = repository.getBpmnModel(definitionId);
        if (model == null) {
            throw new IllegalStateException("流程定义不存在: " + definitionId);
        }
        FlowElement element = model.getFlowElement(nodeId);
        if (element == null) {
            throw new IllegalStateException("节点不存在: " + nodeId + " in " + definitionId);
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("nodeId", nodeId);
        meta.put("nodeName", element.getName());
        meta.put("nodeType", element.getClass().getSimpleName());

        // 只有 userTask 和 receiveTask 需要 actors 和 actionCode
        if (element instanceof UserTask || element instanceof ReceiveTask) {
            meta.put("actors", getExtension(element, "actors"));
            meta.put("actionCode", getExtension(element, "actionCode"));
        }

        return meta;
    }

    public Map<String,Object> nodePolicy(String definitionId,String nodeId,FlowData data){
        BpmnModel model=repository.getBpmnModel(definitionId);
        if(model==null)throw new IllegalStateException("流程定义不存在: "+definitionId);
        FlowElement element=model.getFlowElement(nodeId);
        if(element==null)throw new IllegalStateException("节点不存在: "+nodeId+" in "+definitionId);
        Map<String,Object> policy=new LinkedHashMap<>(data.object(getExtension(element,"ui")));
        Map<String,String> meta=nodeMeta(definitionId,nodeId);
        List<String> actors=split(meta.get("actors")),actions=split(meta.get("actionCode"));
        policy.put("actors",actors);policy.put("actions",actions);policy.put("action",actions.isEmpty()?null:actions.get(0));
        policy.putIfAbsent("title",element.getName());policy.putIfAbsent("actionLabel","确认");policy.putIfAbsent("fields",Collections.emptyList());policy.putIfAbsent("displayFields",Collections.emptyList());
        List<ExtensionElement> handling=element.getExtensionElements().getOrDefault("handling",Collections.emptyList());
        if(!handling.isEmpty()) {
            ExtensionElement declaration = handling.get(0);
            policy.put("handler", declaration.getAttributeValue(null,"handler"));
            policy.put("mobilePage", declaration.getAttributeValue(null,"mobilePage"));
            policy.put("agentPage", declaration.getAttributeValue(null,"agentPage"));
        }
        policy.putAll(stepPresentation(definitionId, nodeId));
        return policy;
    }

    /**
     * 供流程进度条使用的展示策略。服务任务、网关等系统动作不会作为端侧步骤下发；
     * 两个渠道只显示自己可提交的节点，历史还原另走 trail 接口，避免把两种语义混在一起。
     */
    public Map<String,Object> stepPresentation(String definitionId, String nodeId) {
        BpmnModel model = repository.getBpmnModel(definitionId);
        FlowElement element = model == null ? null : model.getFlowElement(nodeId);
        if (!(element instanceof UserTask || element instanceof ReceiveTask)) return Collections.emptyMap();

        Map<String,Object> result = new LinkedHashMap<>();
        List<String> actors = split(getExtension(element, "actors"));
        result.put("mobileVisible", actors.contains("CUSTOMER"));
        result.put("agentVisible", actors.contains("AGENT"));
        List<ExtensionElement> handling = element.getExtensionElements().getOrDefault("handling", Collections.emptyList());
        if (!handling.isEmpty()) {
            ExtensionElement declaration = handling.get(0);
            result.put("mobilePage", declaration.getAttributeValue(null, "mobilePage"));
            result.put("agentPage", declaration.getAttributeValue(null, "agentPage"));
        }
        return result;
    }
    public List<String> notificationTargets(String definitionId,List<String> nodes,boolean terminal){
        org.flowable.bpmn.model.BaseElement process=repository.getBpmnModel(definitionId).getMainProcess();
        Set<String> targets=new java.util.LinkedHashSet<>();
        List<org.flowable.bpmn.model.BaseElement> elements=new java.util.ArrayList<>();
        if(terminal)elements.add(process);else for(String id:nodes)elements.add(repository.getBpmnModel(definitionId).getFlowElement(id));
        for(org.flowable.bpmn.model.BaseElement element:elements)if(element!=null)for(ExtensionElement notify:element.getExtensionElements().getOrDefault("notify",Collections.emptyList()))targets.addAll(split(notify.getAttributeValue(null,"targets")));
        return new java.util.ArrayList<>(targets);
    }
    private List<String> split(String value){return value==null||value.trim().isEmpty()?Collections.emptyList():Arrays.asList(value.split(","));}

    /**
     * 检查节点是否可提交（是 userTask 或 receiveTask）。
     */
    public boolean isSubmittable(String definitionId, String nodeId) {
        BpmnModel model = repository.getBpmnModel(definitionId);
        if (model == null) return false;
        FlowElement element = model.getFlowElement(nodeId);
        return element instanceof UserTask || element instanceof ReceiveTask;
    }

    /**
     * 检查指定角色是否允许提交。
     */
    public boolean isActorAllowed(String definitionId, String nodeId, String actorRole) {
        Map<String, String> meta = nodeMeta(definitionId, nodeId);
        String actors = meta.get("actors");
        if (actors == null || actors.isEmpty()) return false;
        return Arrays.asList(actors.split(",")).contains(actorRole);
    }

    /**
     * 检查动作码是否允许。
     */
    public boolean isActionAllowed(String definitionId, String nodeId, String action) {
        Map<String, String> meta = nodeMeta(definitionId, nodeId);
        String actionCode = meta.get("actionCode");
        if (actionCode == null) return false;
        return Arrays.stream(actionCode.split(","))
                .map(String::trim)
                .anyMatch(action.trim()::equals);
    }

    private String getExtension(FlowElement element, String name) {
        if(element instanceof org.flowable.bpmn.model.ServiceTask)for(org.flowable.bpmn.model.FieldExtension field:((org.flowable.bpmn.model.ServiceTask)element).getFieldExtensions())if(name.equals(field.getFieldName()))return field.getStringValue();
        Map<String, List<ExtensionElement>> extensions = element.getExtensionElements();
        if (extensions == null) return null;
        for (List<ExtensionElement> elements : extensions.values()) {
            for (ExtensionElement extension : elements) {
                String fieldName = extension.getAttributeValue(null, "name");
                if (fieldName == null) fieldName = extension.getAttributeValue("", "name");
                if (!name.equals(fieldName) && !name.equals(extension.getName())) continue;
                String value = extension.getAttributeValue(null, "stringValue");
                if (value == null) value = extension.getAttributeValue("", "stringValue");
                if (value == null) value = extension.getElementText();
                // <flowable:field> commonly wraps its value in <flowable:string>.
                // The field itself has no text, so read the nested element before
                // falling back to an empty policy.  Without this, mobile receives
                // the node name but none of its declared input fields.
                if (value == null || value.trim().isEmpty()) value = childText(extension);
                return value;
            }
        }
        return null;
    }

    private String childText(ExtensionElement parent) {
        for (List<ExtensionElement> children : parent.getChildElements().values()) {
            for (ExtensionElement child : children) {
                String value = child.getElementText();
                if (value != null && !value.trim().isEmpty()) return value;
                value = childText(child);
                if (value != null && !value.trim().isEmpty()) return value;
            }
        }
        return null;
    }
}
