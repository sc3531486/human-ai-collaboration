package com.example.bank.workflow.events;

import com.example.bank.workflow.port.BusinessEventPort;
import com.example.bank.workflow.port.SignalPort;
import com.example.bank.workflow.sop.SopProcessService;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeEventServiceTest {
    @Test void handbackStillNotifiesTheLastAgentWithoutRestoringOwnership() {
        SopProcessService sop = mock(SopProcessService.class);
        SignalPort events = mock(SignalPort.class);
        BusinessEventPort businessEvents = mock(BusinessEventPort.class);
        Map<String,Object> vars = new LinkedHashMap<>();
        vars.put("agent_session_id", "");
        vars.put("last_agent_id", "agent-last");
        Map<String,Object> source = new LinkedHashMap<>();
        source.put("notificationTargets",java.util.Arrays.asList("AGENT"));source.put("vars", vars); source.put("processId", "p1");
        source.put("processStatus", "RUNNING"); source.put("nodeId", "ai_broadcast");
        when(sop.snapshot("p1")).thenReturn(source);
        new NodeEventService(sop, events, businessEvents).snapshot("p1");
        verify(events).notifyAgent(eq("agent-last"), anyMap());
        org.junit.jupiter.api.Assertions.assertEquals("", vars.get("agent_session_id"));
    }
    @Test void onlyConfiguredRecipientsReceiveChangesAndQueriesDoNotRepeatThePush() {
        for(String target:java.util.Arrays.asList("CUSTOMER","AGENT","NONE")) {
            SopProcessService sop=mock(SopProcessService.class);SignalPort events=mock(SignalPort.class);BusinessEventPort businessEvents=mock(BusinessEventPort.class);
            Map<String,Object> vars=new LinkedHashMap<>();vars.put("mobile_peer_id","cust-session");vars.put("customer_id","DEMO-001");vars.put("agent_session_id","agent-1");
            Map<String,Object> source=new LinkedHashMap<>();source.put("vars",vars);source.put("processId","p1");source.put("notificationTargets","NONE".equals(target)?java.util.Collections.emptyList():java.util.Collections.singletonList(target));
            when(sop.snapshot("p1")).thenReturn(source);NodeEventService service=new NodeEventService(sop,events,businessEvents);service.snapshot("p1");service.snapshot("p1");
            if("CUSTOMER".equals(target)){verify(events,times(1)).notifyCustomer(eq("DEMO-001"),anyMap());verify(businessEvents,times(1)).publishCustomer(eq("DEMO-001"),anyMap());verify(events,never()).notifyCustomer(eq("cust-session"),anyMap());verify(events,never()).notifyAgent(anyString(),anyMap());}
            else if("AGENT".equals(target)){verify(events,times(1)).notifyAgent(eq("agent-1"),anyMap());verify(events,never()).notifyCustomer(anyString(),anyMap());}
            else verifyNoInteractions(events);
        }
    }
    @Test void handbackSendsOneReleaseEventAndNoLaterNodePagesToTheAgent(){
        SopProcessService sop=mock(SopProcessService.class);SignalPort events=mock(SignalPort.class);BusinessEventPort businessEvents=mock(BusinessEventPort.class);
        Map<String,Object> vars=new LinkedHashMap<>();vars.put("agent_work_complete",true);vars.put("last_agent_id","agent-1");vars.put("mobile_peer_id","rtc-1");vars.put("customer_id","cust-1");
        Map<String,Object> source=new LinkedHashMap<>();source.put("vars",vars);source.put("notificationTargets",java.util.Collections.singletonList("CUSTOMER"));source.put("processId","p1");
        when(sop.snapshot("p1")).thenReturn(source);NodeEventService service=new NodeEventService(sop,events,businessEvents);service.snapshot("p1");service.snapshot("p1");
        org.mockito.ArgumentCaptor<Map> release=org.mockito.ArgumentCaptor.forClass(Map.class);verify(events,times(1)).notifyAgent(eq("agent-1"),release.capture());org.junit.jupiter.api.Assertions.assertEquals("AGENT_RELEASED",release.getValue().get("type"));
        source=new LinkedHashMap<>(source);source.put("processStatus","COMPLETED");when(sop.snapshot("p1")).thenReturn(source);service.snapshot("p1");verify(events,times(1)).notifyAgent(eq("agent-1"),anyMap());verify(events,times(2)).notifyCustomer(eq("cust-1"),anyMap());
    }
}
