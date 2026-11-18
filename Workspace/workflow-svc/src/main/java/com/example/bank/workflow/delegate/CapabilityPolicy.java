package com.example.bank.workflow.delegate;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.engine.delegate.DelegateExecution;
import java.util.*;
/** 场景能力调用由BPMN声明，公共执行器不维护各交易的节点注册表。 */
final class CapabilityPolicy {
    final String code;final List<String> inputs,outputs;final boolean readOnly;
    static List<CapabilityPolicy> readAll(DelegateExecution execution){
        List<ExtensionElement> definitions=execution.getCurrentFlowElement().getExtensionElements().getOrDefault("capability",Collections.emptyList());
        if(definitions.isEmpty())throw new IllegalStateException("能力节点未配置 capability");
        List<CapabilityPolicy> policies=new ArrayList<>();
        for(ExtensionElement definition:definitions){String phase=attr(definition,"phase"),code=attr(definition,"code"),readOnly=attr(definition,"readOnly");if(!Arrays.asList("start","end","reject","execute").contains(phase)||code==null||!code.matches("[a-zA-Z0-9_.-]+")||!("true".equals(readOnly)||"false".equals(readOnly)))throw new IllegalStateException("能力声明不完整");policies.add(new CapabilityPolicy(code,mappings(attr(definition,"inputs")),mappings(attr(definition,"outputs")),Boolean.parseBoolean(readOnly)));}return policies;
    }
    private CapabilityPolicy(String code,List<String> inputs,List<String> outputs,boolean readOnly){this.code=code;this.inputs=inputs;this.outputs=outputs;this.readOnly=readOnly;}
    static CapabilityPolicy read(DelegateExecution execution){
        List<ExtensionElement> definitions=execution.getCurrentFlowElement().getExtensionElements().getOrDefault("capability",Collections.emptyList());
        if(definitions.size()!=1)throw new IllegalStateException("能力节点必须配置一组 capability："+execution.getCurrentActivityId());
        ExtensionElement definition=definitions.get(0);String code=attr(definition,"code"),readOnly=attr(definition,"readOnly");
        if(code==null||!code.matches("[a-zA-Z0-9_.-]+"))throw new IllegalStateException("缺少有效的能力代码");
        if(!"true".equals(readOnly)&&!"false".equals(readOnly))throw new IllegalStateException("readOnly 必须显式配置 true 或 false");
        return new CapabilityPolicy(code,mappings(attr(definition,"inputs")),mappings(attr(definition,"outputs")),Boolean.parseBoolean(readOnly));
    }
    private static String attr(ExtensionElement definition,String name){String value=definition.getAttributeValue(null,name);if(value==null)value=definition.getAttributeValue("",name);return value==null?null:value.trim();}
    private static List<String> mappings(String text){
        if(text==null)throw new IllegalStateException("必须配置能力输入/输出映射");List<String> mappings=new ArrayList<>();Set<String> names=new HashSet<>();
        if(!text.isEmpty())for(String item:text.split(",")){String mapping=item.trim();if(!mapping.matches("[A-Za-z_][A-Za-z0-9_]*=[A-Za-z_][A-Za-z0-9_.]*"))throw new IllegalStateException("映射必须为字段=来源字段："+mapping);if(!names.add(mapping.split("=")[0]))throw new IllegalStateException("映射目标重复："+mapping);mappings.add(mapping);}return mappings;
    }
}
