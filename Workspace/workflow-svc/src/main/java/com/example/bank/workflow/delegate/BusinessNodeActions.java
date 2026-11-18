package com.example.bank.workflow.delegate;
import org.flowable.engine.delegate.*;
import org.flowable.task.service.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import com.example.bank.workflow.framework.FlowData;
import com.example.bank.workflow.session.BusinessSessionService;
import org.springframework.stereotype.Component;
/** 业务节点的公共进入、完成动作；动作不占用业务流程节点。 */
@Component("businessNodeActions")
public class BusinessNodeActions implements ExecutionListener,TaskListener {
    private final NodeTransitionDelegate transition;private final ApiTaskDelegate capabilities;private final BusinessSessionService sessions;
    public BusinessNodeActions(NodeTransitionDelegate transition,ApiTaskDelegate capabilities,BusinessSessionService sessions){this.transition=transition;this.capabilities=capabilities;this.sessions=sessions;}
    @Override public void notify(DelegateTask task){Object owner=task.getVariable("agent_session_id");if(owner!=null&&!String.valueOf(owner).isEmpty())task.setAssignee(String.valueOf(owner));}
    @Override public void notify(DelegateExecution execution){
        NodeHandlingPolicy policy=NodeHandlingPolicy.read(execution.getCurrentFlowElement());
        if(policy==null)throw new IllegalStateException("节点声明了 businessNodeActions 监听但缺少 flowable:handling 配置："+execution.getCurrentActivityId());
        if("start".equals(execution.getEventName())){
            capabilities.executePhase(execution,"start");
            execution.setVariable("retain_agent","HUMAN".equals(policy.handler)||policy.retainAgent);
            if("HUMAN".equals(policy.handler)){
                execution.setVariable("agent_work_complete",false);
                Object owner=execution.getVariable("agent_session_id");
                if(owner==null||String.valueOf(owner).isEmpty())transition.execute(execution);
            }
        }else if("end".equals(execution.getEventName())){
            if("REJECT".equals(execution.getVariable("action"))){
                capabilities.executePhase(execution,"reject");
                if("AUTHORIZER".equals(execution.getVariable("participant_role"))){Object owner=execution.getVariable("agent_session_id");execution.setVariable("last_agent_id",owner);sessions.release(execution.getProcessInstanceId());execution.setVariable("agent_work_complete",true);}
                return;
            }
            capabilities.executePhase(execution,"end");
            if("HUMAN".equals(policy.handler)&&!policy.retainAgent){
                Object owner=execution.getVariable("agent_session_id");if(owner!=null)execution.setVariable("last_agent_id",owner);
                sessions.release(execution.getProcessInstanceId());execution.setVariable("retain_agent",false);execution.setVariable("agent_work_complete",true);
            }
        }
    }
}
