package com.example.bank.business.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import java.util.*;

/** 可靠消费流程结果；台账与事件收据同事务，成功指标直接从台账聚合。 */
@RestController
public class ProcessEventController {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.application.CallSessionService calls;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.port.RoutingPort routing;
    public ProcessEventController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS process_event_inbox(event_id VARCHAR(160) PRIMARY KEY, received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");}
    @PostMapping("/internal/process-events")
    @Transactional
    @SuppressWarnings("unchecked")
    public void receive(@RequestBody Map<String,Object> event) {
        String id=String.valueOf(event.getOrDefault("eventId",""));
        if(id.isEmpty() || !(event.get("variables") instanceof Map)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"事件缺少标识或上下文");
        if(jdbc.update("INSERT IGNORE INTO process_event_inbox(event_id) VALUES(?)",id)==0)return;
        Map<String,Object> vars=(Map<String,Object>)event.get("variables");
        if(vars.get("parent_process_id")!=null&&!String.valueOf(vars.get("parent_process_id")).isEmpty()){
            Object reviewer=vars.get("last_agent_id");if(reviewer!=null)calls.leaveAuthorization(String.valueOf(event.get("processId")),String.valueOf(reviewer));routing.complete(String.valueOf(event.get("processId")));return;
        }
        String status="CANCELLED".equals(event.get("processStatus"))?"REJECTED":Boolean.TRUE.equals(vars.get("business_success"))?"COMPLETED":"FAILED";
        int updated=jdbc.update("UPDATE biz_record SET status=?,completed_time=CURRENT_TIMESTAMP,agent_id=COALESCE(?,agent_id),amount=COALESCE(?,amount),payee=COALESCE(?,payee) WHERE process_instance_id=?",
                status,vars.get("last_agent_id"),vars.get("amount"),vars.get("payee"),event.get("processId"));
        if(updated==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"业务台账尚未就绪，请稍后重试");
    }
}
