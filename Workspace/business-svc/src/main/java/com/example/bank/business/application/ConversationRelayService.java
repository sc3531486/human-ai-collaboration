package com.example.bank.business.application;

import com.example.bank.business.evidence.InteractionTraceService;
import com.example.bank.business.port.ProcessPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.example.bank.business.evidence.InteractionTraceService.text;

/** 人工会话消息：验证流程绑定、留痕，再经信令投递；完全不调用模型。 */
@Service
public class ConversationRelayService {
    private final ProcessPort processes;
    private final InteractionTraceService traces;
    private final RestTemplate http;
    private final String signalUrl;
    @org.springframework.beans.factory.annotation.Autowired private CallSessionService calls;
    public ConversationRelayService(ProcessPort processes, InteractionTraceService traces, RestTemplate http,
            @Value("${business.signaling-base-url}") String signalUrl) {
        this.processes = processes; this.traces = traces; this.http = http; this.signalUrl = signalUrl;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> send(Map<String,Object> body) {
        String processId = text(body.get("processId"));
        Map<String,Object> call=calls.byProcess(processId);
        if(call==null||!"OPEN".equals(call.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"人工通话已经结束");
        Map<String,Object> state=new HashMap<>();state.put("bizNo",call.get("bizNo"));state.put("nodeName","");
        Map<String,Object> vars=new HashMap<>();vars.put("agent_session_id",call.get("agentId"));vars.put("customer_id",call.get("customerId"));vars.put("mobile_peer_id",call.get("customerPeerId"));
        String role = text(body.getOrDefault("actorRole", "AGENT"));
        String actor = text(body.get("actorId"));
        String agentId = text(vars.get("agent_session_id"));
        if (agentId.isEmpty()) agentId = text(vars.get("agent_id"));
        String customerId = text(vars.get("customer_id"));
        if (agentId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "人工会话尚未接入或已经结束");
        }
        String expected = "CUSTOMER".equals(role) ? text(vars.get("mobile_peer_id")) : agentId;
        List<Map<String,Object>> members=(List<Map<String,Object>>)call.get("participants");
        if("AGENT".equals(role)) for(Map<String,Object> member:members)if(!"CUSTOMER".equals(member.get("role"))&&actor.equals(member.get("actorId")))expected=actor;
        if (!("CUSTOMER".equals(role) || "AGENT".equals(role)) || actor.isEmpty() || !actor.equals(expected)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "消息来源与业务会话不匹配");
        }
        String message = text(body.get("text")).trim();
        if (message.isEmpty() || message.length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息需为1至1000字");
        String bizNo = text(state.get("bizNo"));
        String eventId = text(body.get("eventId"));
        if (eventId.isEmpty()) eventId = "chat-" + UUID.randomUUID();
        Map<String,Object> payload = new LinkedHashMap<String,Object>();
        payload.put("text", message); payload.put("actorId", actor);
        String eventType = "CUSTOMER".equals(role) ? "CHAT_CUSTOMER_MESSAGE" : "CHAT_AGENT_MESSAGE";
        boolean saved = traces.append(bizNo, eventId, processId, text(state.get("nodeName")), role, eventType, payload, text(body.get("clientTime")));
        Map<String,Object> event = new LinkedHashMap<String,Object>();
        event.put("type", "CHAT_MESSAGE"); event.put("processId", processId); event.put("bizNo", bizNo);
        event.put("eventId", eventId); event.put("eventType", eventType); event.put("actorRole", role);
        event.put("payload", payload); event.put("createdAt", System.currentTimeMillis());
        for(Map<String,Object> member:members) {
            boolean customerMember="CUSTOMER".equals(member.get("role"));
            if(customerMember && "CUSTOMER".equals(role) || !customerMember && actor.equals(member.get("actorId")))continue;
            Map<String,Object> outbound=new LinkedHashMap<>(event);outbound.put("processId",member.get("processId"));outbound.put("callId",call.get("callId"));
            Map<String,Object> sent=http.postForObject(signalUrl+"/internal/"+(customerMember?"customer":"agent")+"/{id}/publish",outbound,Map.class,String.valueOf(member.get("actorId")));
            if(sent==null||!Boolean.TRUE.equals(sent.get("delivered")))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"消息已留痕，信令投递失败，请重试");
        }
        event.put("success", true); event.put("duplicate", !saved);
        return event;
    }
}
