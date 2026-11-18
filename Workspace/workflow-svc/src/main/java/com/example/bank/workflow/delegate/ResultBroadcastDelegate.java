package com.example.bank.workflow.delegate;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
/** 结果页面由数字人展示，不等待客户端办结动作。 */
@Component("resultBroadcastDelegate")
public class ResultBroadcastDelegate implements JavaDelegate {
    @Override public void execute(DelegateExecution execution){execution.setVariable("result_node_id",execution.getCurrentActivityId());}
}
