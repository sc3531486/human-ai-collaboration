package com.example.bank.workflow.delegate;

import com.example.bank.workflow.framework.*;
import org.flowable.engine.delegate.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;

/** 能力调用节点：能力清单、输入输出映射与执行阶段全部来自 BPMN 扩展声明，缺失即报错。 */
@Component("apiTaskDelegate")
public class ApiTaskDelegate implements JavaDelegate {
    private final RestTemplate http;
    private final String baseUrl;
    private final FlowData data;
    private final NodeSubmissionService submissions;

    public ApiTaskDelegate(RestTemplate http, @Value("${workflow.business-base-url:http://localhost:8080}") String baseUrl,
                           FlowData data, NodeSubmissionService submissions) {
        this.http=http; this.baseUrl=baseUrl; this.data=data; this.submissions=submissions;
    }

    @Override public void execute(DelegateExecution execution){executePhase(execution,"execute");execution.setVariable("result_node_id",execution.getCurrentActivityId());}
    public void executePhase(DelegateExecution execution,String phase){
        List<org.flowable.bpmn.model.ExtensionElement> capabilities=execution.getCurrentFlowElement().getExtensionElements().getOrDefault("capability",Collections.emptyList());
        // execute 阶段由 ${apiTaskDelegate} 自身触发：没有 capability 声明就是配置错误，
        // 不能静默空转后继续推进流程。end 阶段由等待节点的 businessNodeActions 统一调用，
        // 节点本来就可以没有 end 阶段能力，缺失即无事可做。
        if(capabilities.isEmpty()){
            if("execute".equals(phase))throw new IllegalStateException("能力节点必须显式声明 capability："+execution.getCurrentActivityId());
            return;
        }
        CapabilityPolicy.readAll(execution);
        for(org.flowable.bpmn.model.ExtensionElement config:capabilities){
            String configured=config.getAttributeValue(null,"phase");
            if(configured==null||configured.trim().isEmpty())throw new IllegalStateException("能力节点必须显式声明 phase："+execution.getCurrentActivityId());
            String readOnly=config.getAttributeValue(null,"readOnly");
            if(!"true".equals(readOnly)&&!"false".equals(readOnly))throw new IllegalStateException("能力节点必须显式声明 readOnly："+execution.getCurrentActivityId());
            if(!phase.equals(configured.trim()))continue;
            String code=config.getAttributeValue(null,"code");
            String inputs=config.getAttributeValue(null,"inputs");
            String outputs=config.getAttributeValue(null,"outputs");
            if(code==null||code.trim().isEmpty()||inputs==null||outputs==null)throw new IllegalStateException("能力声明缺少 code/inputs/outputs："+execution.getCurrentActivityId());
            Map<String,Object> input=new LinkedHashMap<>();
            for(String mapping:inputs.split(",")){if(mapping.isEmpty())continue;String[] parts=mapping.split("=",2);Object value=data.value(execution,parts[1]);if(value!=null)input.put(parts[0],value);}
            input.put("bizNo",data.value(execution,"biz_no"));input.put("processId",execution.getProcessInstanceId());input.put("nodeName",execution.getCurrentFlowElement().getName());
            String operation=execution.getProcessInstanceId()+":"+execution.getCurrentActivityId()+":"+code;
            operation+=":"+phase+":"+String.valueOf(execution.getVariable("submission_request_id"));
            Map<String,Object> request=new LinkedHashMap<>();request.put("operationId",operation);request.put("payload",input);
            Map<?,?> response=http.postForObject(baseUrl+"/internal/capabilities/{capability}",request,Map.class,code);
            if(response==null||!"SUCCEEDED".equals(response.get("status"))||!(response.get("data") instanceof Map))throw new IllegalStateException("能力未返回成功："+code);
            Map<String,Object> body=data.object(response.get("data")),output=new LinkedHashMap<>();body.put("_succeeded",true);
            for(String mapping:outputs.split(",")){if(mapping.isEmpty())continue;String[] parts=mapping.split("=",2);Object value=data.read(body,parts[1]);if(value!=null)output.put(parts[0],value);}
            execution.setVariable("results",data.encode(data.merge(data.object(execution.getVariable("results")),output)));
            submissions.record(execution.getProcessInstanceId(),execution.getCurrentActivityId(),execution.getId(),execution.getProcessDefinitionId(),output);
        }
    }
}
