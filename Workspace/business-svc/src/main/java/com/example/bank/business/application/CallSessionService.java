package com.example.bank.business.application;

import com.example.bank.business.port.ProcessPort;
import com.example.bank.business.port.RoutingPort;
import com.example.bank.business.port.MediaPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.annotation.PostConstruct;
import java.util.*;

/** 通话事实独立于交易流程；流程完成不结束通话，结束消息用持久化事件投递。 */
@Service
public class CallSessionService {
    private final com.example.bank.business.service.ServiceBusinessCatalog businessCatalog;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RoutingPort routing;
    private final MediaPort media;
    public CallSessionService(JdbcTemplate jdbc,ObjectMapper json,RoutingPort routing,MediaPort media,com.example.bank.business.service.ServiceBusinessCatalog businessCatalog) {
        this.jdbc=jdbc;this.json=json;this.routing=routing;this.media=media;this.businessCatalog=businessCatalog;
    }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_call_session(call_id VARCHAR(64) PRIMARY KEY, process_id VARCHAR(64) UNIQUE NOT NULL, biz_no VARCHAR(64), room_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, customer_id VARCHAR(100) NOT NULL, customer_peer_id VARCHAR(100), agent_id VARCHAR(100) NOT NULL, agent_peer_id VARCHAR(100), context_json MEDIUMTEXT NOT NULL, end_initiator VARCHAR(16), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, ended_at TIMESTAMP NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_call_member(call_id VARCHAR(64) NOT NULL, actor_id VARCHAR(100) NOT NULL, actor_role VARCHAR(20) NOT NULL, peer_id VARCHAR(100) NOT NULL, process_id VARCHAR(64) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, PRIMARY KEY(call_id,actor_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_call_event(event_id VARCHAR(100) PRIMARY KEY, call_id VARCHAR(64) NOT NULL, event_json MEDIUMTEXT NOT NULL, delivered BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }
    public synchronized Map<String,Object> register(String processId,Map<String,Object> context,String roomId) {
        String customer=required(context,"customer_id"),agent=required(context,"agent_session_id");
        List<Map<String,Object>> existing=jdbc.queryForList("SELECT * FROM biz_call_session WHERE process_id=?",processId);
        String id=!existing.isEmpty()&&"OPEN".equals(existing.get(0).get("status")) ? text(existing.get(0).get("call_id")) : "CALL-"+UUID.randomUUID();
        jdbc.update("INSERT INTO biz_call_session(call_id,process_id,biz_no,room_id,status,customer_id,customer_peer_id,agent_id,agent_peer_id,context_json) VALUES(?,?,?,?,'OPEN',?,?,?,?,?) ON DUPLICATE KEY UPDATE call_id=VALUES(call_id),room_id=VALUES(room_id),status='OPEN',customer_id=VALUES(customer_id),customer_peer_id=VALUES(customer_peer_id),agent_id=VALUES(agent_id),agent_peer_id=VALUES(agent_peer_id),context_json=VALUES(context_json),end_initiator=NULL,ended_at=NULL",
                id,processId,context.get("biz_no"),roomId,customer,context.get("mobile_peer_id"),agent,context.get("agent_peer_id"),encode(context));
        member(id,customer,"CUSTOMER",text(context.get("mobile_peer_id")),processId);
        member(id,agent,"AGENT",text(context.get("agent_peer_id")),processId);
        businessCatalog.attach(id,processId,text(context.get("biz_no")));
        return view(requiredCall(id));
    }
    public void member(String callId,String actor,String role,String peer,String processId) {
        jdbc.update("INSERT INTO biz_call_member(call_id,actor_id,actor_role,peer_id,process_id,active) VALUES(?,?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE peer_id=VALUES(peer_id),process_id=VALUES(process_id),actor_role=VALUES(actor_role),active=TRUE",callId,actor,role,peer,processId);
    }
    @Transactional public void abandon(String processId) {
        Map<String,Object> call=byProcess(processId);if(call==null||!"OPEN".equals(call.get("status")))return;
        if("AUTHORIZER".equals(call.get("role"))){leaveAuthorization(processId,text(call.get("localActorId")),false);return;}
        String id=String.valueOf(call.get("callId"));
        jdbc.update("UPDATE biz_call_session SET status='ENDED',end_initiator='SYSTEM',ended_at=CURRENT_TIMESTAMP WHERE call_id=?",id);
        Map<String,Object> event=view(requiredCall(id));event.put("type","CALL_ENDED");event.put("eventId",id+":ended");event.put("initiatorRole","SYSTEM");
        jdbc.update("INSERT IGNORE INTO biz_call_event(event_id,call_id,event_json) VALUES(?,?,?)",id+":ended",id,encode(event));
    }
    public Map<String,Object> byProcess(String processId) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT s.* FROM biz_call_session s WHERE s.process_id=? OR s.call_id IN (SELECT call_id FROM biz_call_member WHERE process_id=?)",processId,processId);
        if(rows.isEmpty())return null;
        Map<String,Object> value=view(rows.get(0));value.put("parentProcessId",value.get("processId"));value.put("processId",processId);
        for(Map<String,Object> member:jdbc.queryForList("SELECT * FROM biz_call_member WHERE call_id=? AND process_id=? AND actor_role<>'CUSTOMER'",value.get("callId"),processId)){value.put("role",member.get("actor_role"));value.put("localPeerId",member.get("peer_id"));value.put("localActorId",member.get("actor_id"));if("OPEN".equals(value.get("status"))&&!Boolean.TRUE.equals(member.get("active"))&&"AUTHORIZER".equals(member.get("actor_role")))value.put("status","LEFT");}
        return value;
    }
    @Transactional public Map<String,Object> joinAuthorization(String parentProcessId,String childProcessId,String agentId,String peerId){
        if(agentId==null||agentId.trim().isEmpty()||peerId==null||peerId.trim().isEmpty())bad("授权坐席身份及媒体用户不能为空");
        Map<String,Object> call=byProcess(parentProcessId);if(call==null||!"OPEN".equals(call.get("status")))bad("原视频通话已经结束，不能加入授权坐席");
        if(agentId.equals(call.get("agentId")))bad("原办理坐席不能作为独立授权坐席");
        String id=text(call.get("callId"));member(id,agentId,"AUTHORIZER",peerId,childProcessId);
        Map<String,Object> event=view(requiredCall(id));event.put("joiningActorId",agentId);media.updateParticipants(text(call.get("roomId")),event);
        return Collections.<String,Object>singletonMap("roomId",call.get("roomId"));
    }
    public void leaveAuthorization(String childProcessId,String actorId){
        leaveAuthorization(childProcessId,actorId,true);
    }
    private void leaveAuthorization(String childProcessId,String actorId,boolean releaseRouting){
        Map<String,Object> call=byProcess(childProcessId);if(call==null||!"AUTHORIZER".equals(call.get("role")))return;
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM biz_call_member WHERE call_id=? AND process_id=? AND actor_id=? AND actor_role='AUTHORIZER'",call.get("callId"),childProcessId,actorId);
        if(rows.size()!=1)bad("授权成员身份不匹配");Map<String,Object> row=rows.get(0);
        jdbc.update("UPDATE biz_call_member SET active=FALSE WHERE call_id=? AND actor_id=?",call.get("callId"),actorId);
        Map<String,Object> leaving=new LinkedHashMap<>();leaving.put("actorId",actorId);leaving.put("role","AUTHORIZER");leaving.put("processId",childProcessId);leaving.put("peerId",row.get("peer_id"));
        Map<String,Object> event=view(requiredCall(text(call.get("callId"))));event.put("leavingMember",leaving);media.updateParticipants(text(call.get("roomId")),event);if(releaseRouting)routing.complete(childProcessId);
    }
    public Map<String,Object> context(String processId) {
        Map<String,Object> call=byProcess(processId);
        if(call==null)return null;
        Map<String,Object> vars=new LinkedHashMap<>();vars.put("customer_id",call.get("customerId"));vars.put("agent_session_id",call.get("agentId"));
        vars.put("agent_peer_id",call.get("agentPeerId"));vars.put("biz_no",call.get("bizNo"));vars.put("video_room_id",call.get("roomId"));
        if("AUTHORIZER".equals(call.get("role"))){vars.put("agent_session_id",call.get("localActorId"));vars.put("agent_peer_id",call.get("localPeerId"));}
        vars.put("video_room_state","OPEN".equals(call.get("status"))?"OPEN":"CLOSED");vars.put("call_active","OPEN".equals(call.get("status")));vars.put("call_id",call.get("callId"));vars.put("call_participants",call.get("participants"));return vars;
    }
    @Transactional public Map<String,Object> end(String callId,String actorId,String role) {
        jdbc.queryForList("SELECT call_id FROM biz_call_session WHERE call_id=? FOR UPDATE",callId);
        Map<String,Object> row=requiredCall(callId);
        if(!Arrays.asList("CUSTOMER","AGENT").contains(role) || !actorId.equals(text(row.get("CUSTOMER".equals(role)?"customer_id":"agent_id"))))bad("当前用户不能结束该通话");
        if("ENDED".equals(row.get("status")))return view(row);
        jdbc.update("UPDATE biz_call_session SET status='ENDED',end_initiator=?,ended_at=CURRENT_TIMESTAMP WHERE call_id=? AND status='OPEN'",role,callId);
        Map<String,Object> event=view(requiredCall(callId));event.put("type","CALL_ENDED");event.put("eventId",callId+":ended");event.put("initiatorRole",role);
        jdbc.update("INSERT IGNORE INTO biz_call_event(event_id,call_id,event_json) VALUES(?,?,?)",callId+":ended",callId,encode(event));
        return event;
    }
    @Scheduled(fixedDelayString="${business.call-event-delay-ms:1000}")
    public void deliver() {
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM biz_call_event WHERE delivered=FALSE ORDER BY created_at LIMIT 30")) {
            try {
                Map<String,Object> event=decode(row.get("event_json"));
                media.hangupRoom(String.valueOf(event.get("roomId")),event);
                for(Map<String,Object> member:jdbc.queryForList("SELECT DISTINCT process_id FROM biz_call_member WHERE call_id=?",row.get("call_id")))routing.complete(text(member.get("process_id")));
                jdbc.update("UPDATE biz_call_member SET active=FALSE WHERE call_id=?",row.get("call_id"));
                jdbc.update("UPDATE biz_call_event SET delivered=TRUE WHERE event_id=?",row.get("event_id"));
            } catch(RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("通话结束事件待投递 eventId={}",row.get("event_id"),error); }
        }
    }
    public Map<String,Object> requiredCall(String id) {List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM biz_call_session WHERE call_id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"通话不存在");return rows.get(0);}
    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> value=new LinkedHashMap<>();value.put("callId",row.get("call_id"));value.put("processId",row.get("process_id"));value.put("bizNo",row.get("biz_no"));value.put("roomId",row.get("room_id"));value.put("status",row.get("status"));
        value.put("customerId",row.get("customer_id"));value.put("customerPeerId",row.get("customer_peer_id"));value.put("agentId",row.get("agent_id"));value.put("agentPeerId",row.get("agent_peer_id"));value.put("initiatorRole",row.get("end_initiator"));
        List<Map<String,Object>> participants=new ArrayList<>();for(Map<String,Object> member:jdbc.queryForList("SELECT * FROM biz_call_member WHERE call_id=? AND active=TRUE",row.get("call_id"))) {
            Map<String,Object> p=new LinkedHashMap<>();p.put("actorId",member.get("actor_id"));p.put("role",member.get("actor_role"));p.put("peerId",member.get("peer_id"));p.put("processId",member.get("process_id"));participants.add(p);
        }value.put("participants",participants);return value;
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> decode(Object value){try{return json.readValue(String.valueOf(value),Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private String required(Map<String,Object> vars,String key){String value=text(vars.get(key));if(value.isEmpty())bad("缺少通话身份："+key);return value;}
    private String text(Object value){return value==null?"":String.valueOf(value);}
    private void bad(String message){throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
