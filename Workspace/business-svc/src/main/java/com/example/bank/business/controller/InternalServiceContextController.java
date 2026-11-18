package com.example.bank.business.controller;

import com.example.bank.business.application.ReceptionAcceptanceService;
import com.example.bank.business.contract.QueueTicket;
import com.example.bank.business.port.AgentPort;
import com.example.bank.business.port.MediaPort;
import com.example.bank.business.port.ProcessPort;
import com.example.bank.business.port.RoutingPort;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** 专业服务仅使用业务会话上下文和语义动作，不直接访问另一个专业服务或流程数据库。 */
@RestController
@RequestMapping("/internal")
public class InternalServiceContextController {
    private final ProcessPort process;
    private final ReceptionAcceptanceService acceptance;
    private final RoutingPort routing;
    private final AgentPort agents;
    private final MediaPort media;
    private final RestTemplate http;
    private final String workflowBase;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.application.CallSessionService calls;
    public InternalServiceContextController(ProcessPort process, ReceptionAcceptanceService acceptance,
            RoutingPort routing, AgentPort agents, MediaPort media, RestTemplate http,
            @Value("${business.workflow-base-url}") String workflowBase) {
        this.process=process; this.acceptance=acceptance; this.routing=routing;
        this.agents=agents; this.media=media; this.http=http; this.workflowBase=workflowBase;
    }
    @GetMapping("/session-context/{id}")
    public Map<String, Object> context(@PathVariable String id) {
        Map<String,Object> vars;
        Map<String,Object> callContext=calls.context(id);
        try { vars=new LinkedHashMap<>(process.variables(id)); }
        catch(org.springframework.web.client.HttpClientErrorException error) {
            if(error.getRawStatusCode()!=404 || callContext==null)throw error;
            vars=new LinkedHashMap<>();
        }
        if(callContext!=null)vars.putAll(callContext);
        Map<String,Object> context=new LinkedHashMap<>();
        for(String field:Arrays.asList("customer_id","agent_session_id","agent_id","agent_peer_id","biz_no",
                "retain_agent","call_active","call_id","call_participants","video_room_id","video_room_state","video_room_open_time","video_room_close_time")) {
            if(vars.containsKey(field)) context.put(field,vars.get(field));
        }
        return context;
    }
    @PostMapping("/session-context/{id}/media-state")
    public void mediaState(@PathVariable String id, @RequestBody Map<String, Object> changes) {
        Set<String> allowed = new HashSet<>(Arrays.asList("video_room_id", "video_room_state", "video_room_open_time", "video_room_close_time"));
        if (!allowed.containsAll(changes.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "媒体服务只能更新房间状态字段");
        process.setVariables(id, changes);
    }
    @GetMapping("/session-context/{id}/node/{node}/active")
    public Map<String, Object> active(@PathVariable String id, @PathVariable String node) {
        return http.getForObject(workflowBase + "/internal/process/{id}/node/{node}/active", Map.class, id, node);
    }
    @PostMapping("/reception/{id}/accept")
    public Map<String, Object> accept(@PathVariable String id, @RequestBody Map<String, String> input) {
        return acceptance.accept(id, input.get("agentId"), input.get("peerId"));
    }
    @PostMapping("/reception/route")
    public QueueTicket route(@RequestBody Map<String, Object> input) { return routing.route(input); }
    @PostMapping("/reception/agent/{id}/success")
    public void success(@PathVariable String id) { agents.recordSuccess(id); }
    @PostMapping("/reception/{id}/close-room")
    public void close(@PathVariable String id) { media.closeRoom(id); }
}
