package com.example.bank.business.application;

import com.example.bank.business.evidence.InteractionTraceService;
import com.example.bank.business.port.ProcessPort;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.bank.business.controller.BalanceController;
import com.example.bank.business.service.DigitalHumanService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversationRelayServiceTest {
    @Test void activeHumanSessionNeverCallsDigitalHumanModel() {
        ProcessPort processes = mock(ProcessPort.class);
        DigitalHumanService model = mock(DigitalHumanService.class);
        Map<String,Object> vars = new HashMap<>();
        vars.put("agent_session_id", "agent-1"); vars.put("takeover_mode", "av");
        when(processes.variables("p1")).thenReturn(vars);
        BalanceController controller = new BalanceController();
        ReflectionTestUtils.setField(controller, "process", processes);
        ReflectionTestUtils.setField(controller, "digitalHumanService", model);
        ReflectionTestUtils.setField(controller, "calls", mock(CallSessionService.class));
        Map<String,Object> input = new HashMap<>();
        input.put("processId", "p1"); input.put("text", "帮我核实");
        assertThrows(ResponseStatusException.class, () -> controller.aiChat(input));
        verifyNoInteractions(model);
    }
    @Test void customerMessageTargetsBoundAgentAndIsTraced() {
        ProcessPort process = mock(ProcessPort.class);
        InteractionTraceService traces = mock(InteractionTraceService.class);
        RestTemplate http = mock(RestTemplate.class);
        Map<String,Object> vars = new HashMap<>();
        vars.put("agent_session_id", "agent-1"); vars.put("mobile_peer_id", "cust-session");
        vars.put("customer_id", "customer-1"); vars.put("takeover_mode", "av");
        Map<String,Object> state = new HashMap<>();
        state.put("processStatus", "RUNNING"); state.put("bizNo", "biz-1"); state.put("vars", vars);
        when(process.state("p1")).thenReturn(state);
        when(http.postForObject(anyString(), any(), eq(Map.class), eq("agent-1"))).thenReturn(Collections.singletonMap("delivered", true));
        Map<String,Object> input = new HashMap<>();
        input.put("processId", "p1"); input.put("actorRole", "CUSTOMER"); input.put("actorId", "cust-session");
        input.put("eventId", "event-1"); input.put("text", "你好");
        ConversationRelayService service = new ConversationRelayService(process, traces, http, "http://signal");
        CallSessionService calls=mock(CallSessionService.class);ReflectionTestUtils.setField(service,"calls",calls);
        Map<String,Object> call=new HashMap<>();call.put("status","OPEN");call.put("bizNo","biz-1");call.put("agentId","agent-1");call.put("customerId","customer-1");call.put("customerPeerId","cust-session");when(calls.byProcess("p1")).thenReturn(call);
        List<Map<String,Object>> members=new ArrayList<>();members.add(new HashMap<String,Object>(){{put("actorId","agent-1");put("role","AGENT");put("processId","p1");}});members.add(new HashMap<String,Object>(){{put("actorId","customer-1");put("role","CUSTOMER");put("processId","p1");}});call.put("participants",members);
        Map<String,Object> sent = service.send(input);
        assertEquals("CHAT_CUSTOMER_MESSAGE", sent.get("eventType"));
        verify(http).postForObject(eq("http://signal/internal/agent/{id}/publish"), any(), eq(Map.class), eq("agent-1"));
        verify(traces).append(eq("biz-1"), eq("event-1"), eq("p1"), anyString(), eq("CUSTOMER"), eq("CHAT_CUSTOMER_MESSAGE"), anyMap(), anyString());
        call.put("status", "ENDED");
        assertThrows(ResponseStatusException.class, () -> service.send(input));
        input.put("actorId", "unrelated-customer"); call.put("status", "OPEN");
        assertThrows(ResponseStatusException.class, () -> service.send(input));
    }
}
