package com.example.bank.workflow.events;

import com.example.bank.workflow.framework.FlowData;
import org.flowable.common.engine.api.delegate.event.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;
import javax.annotation.PostConstruct;
import java.util.*;

/** 终态事件在引擎事务内落盘，事务外投递；业务库失败时保留待发记录。 */
@Component
public class ProcessOutcomeOutbox implements FlowableEventListener {
    private final JdbcTemplate jdbc;
    private final FlowData data;
    private final RestTemplate http;
    private final String baseUrl;
    public ProcessOutcomeOutbox(JdbcTemplate jdbc, FlowData data, RestTemplate http,
            @Value("${workflow.business-base-url:http://localhost:8080}") String baseUrl) {
        this.jdbc=jdbc;this.data=data;this.http=http;this.baseUrl=baseUrl;
    }
    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_outbox(event_id VARCHAR(160) PRIMARY KEY, payload MEDIUMTEXT NOT NULL, delivered BOOLEAN NOT NULL DEFAULT FALSE, attempts INT NOT NULL DEFAULT 0)");
    }
    @Override public void onEvent(FlowableEvent event) {
        if (!(event instanceof FlowableEntityEvent)) return;
        Object entity=((FlowableEntityEvent)event).getEntity();
        if (!(entity instanceof DelegateExecution)) return;
        DelegateExecution execution=(DelegateExecution)entity;
        String processId=execution.getProcessInstanceId();
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("eventId",processId+":terminal");body.put("processId",processId);
        body.put("processStatus",event.getType()==FlowableEngineEventType.PROCESS_CANCELLED?"CANCELLED":"COMPLETED");
        body.put("variables",data.project(execution.getVariables()));
        jdbc.update("INSERT IGNORE INTO workflow_outbox(event_id,payload) VALUES(?,?)",body.get("eventId"),data.encode(body));
    }
    @Scheduled(fixedDelayString="${workflow.outbox-delay-ms:2000}")
    public void publish() {
        for(Map<String,Object> row:jdbc.queryForList("SELECT event_id,payload FROM workflow_outbox WHERE delivered=FALSE ORDER BY attempts,event_id LIMIT 20")) {
            try {
                http.postForObject(baseUrl+"/internal/process-events",data.object(row.get("payload")),Void.class);
                jdbc.update("UPDATE workflow_outbox SET delivered=TRUE WHERE event_id=?",row.get("event_id"));
            } catch(RuntimeException error) {
                jdbc.update("UPDATE workflow_outbox SET attempts=attempts+1 WHERE event_id=?",row.get("event_id"));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("流程结果投递失败，保留重试：{}",row.get("event_id"));
            }
        }
    }
    @Override public boolean isFailOnException(){return true;}
    @Override public boolean isFireOnTransactionLifecycleEvent(){return false;}
    @Override public String getOnTransaction(){return null;}
    @Override public Collection<? extends FlowableEventType> getTypes(){return Arrays.asList(
            FlowableEngineEventType.PROCESS_COMPLETED,FlowableEngineEventType.PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT,FlowableEngineEventType.PROCESS_CANCELLED);}
}
