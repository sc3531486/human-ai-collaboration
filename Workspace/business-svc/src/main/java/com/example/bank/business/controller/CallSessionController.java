package com.example.bank.business.controller;
import com.example.bank.business.application.CallSessionService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/calls")
public class CallSessionController {
    private final CallSessionService calls;
    public CallSessionController(CallSessionService calls){this.calls=calls;}
    @GetMapping("/by-process/{processId}") public Map<String,Object> byProcess(@PathVariable String processId){return calls.byProcess(processId);}
    @PostMapping("/{id}/hangup") public Map<String,Object> hangup(@PathVariable String id,@RequestBody Map<String,String> input){return calls.end(id,input.getOrDefault("actorId",""),input.getOrDefault("actorRole",""));}
    @PostMapping("/{id}/failed") public void failed(@PathVariable String id,@RequestBody Map<String,String> input){Map<String,Object> call=calls.requiredCall(id);if(String.valueOf(call.get("agent_id")).equals(input.get("actorId")))calls.abandon(String.valueOf(call.get("process_id")));else leave(id,input);}
    @PostMapping("/{id}/leave") public void leave(@PathVariable String id,@RequestBody Map<String,String> input){Map<String,Object> call=calls.byProcess(input.getOrDefault("processId",""));if(call==null||!id.equals(call.get("callId"))||!"AUTHORIZER".equals(call.get("role"))||!input.getOrDefault("actorId","").equals(call.get("localActorId")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"不是当前授权成员");calls.leaveAuthorization(input.get("processId"),input.get("actorId"));}
}
