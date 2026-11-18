package com.example.bank.business.application;

import com.example.bank.business.mock.MockResourceCatalog;
import com.example.bank.business.port.ProcessPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizationServiceTest {
    @Test void independentDecisionIsBoundToUnchangedBusinessSnapshot() throws Exception {
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:auth-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        ProcessPort processes=mock(ProcessPort.class);CallSessionService calls=mock(CallSessionService.class);
        MockResourceCatalog resources=mock(MockResourceCatalog.class);
        Map<String,Object> resource=new HashMap<>();resource.put("agentId","reviewer");resource.put("skills",Collections.singletonList("AUTHORIZATION"));
        when(resources.read()).thenReturn(Collections.singletonMap("agents",Collections.singletonList(resource)));
        AuthorizationService service=new AuthorizationService(jdbc,new ObjectMapper(),processes,calls);ReflectionTestUtils.setField(service,"resources",resources);service.initialize();
        Map<String,Object> parent=new HashMap<>();parent.put("agent_session_id","requester");parent.put("biz_no","B");parent.put("customer_id","C");parent.put("payload","{\"selected_card_id\":\"CARD\",\"id_front_photo\":\"/records/front.png\"}");parent.put("results","{\"identityAuditId\":\"audit\",\"faceScore\":89,\"faceThreshold\":90}");
        when(processes.variables("parent")).thenReturn(parent);when(calls.byProcess("parent")).thenReturn(Collections.singletonMap("status","OPEN"));
        Map<String,Object> input=new HashMap<>();input.put("processId","parent");input.put("bizNo","B");input.put("operationId","once");input.put("reason","低于阈值");
        String id=(String)service.create(input).get("authorizationId");assertEquals(id,service.create(input).get("authorizationId"));
        assertThrows(ResponseStatusException.class,()->service.requireApproved(id,"parent"));
        Map<String,Object> decision=new HashMap<>();decision.put("authorizationId",id);decision.put("processId","child");decision.put("agentId","requester");decision.put("decision","AUTHORIZE");
        assertThrows(ResponseStatusException.class,()->service.decide(decision));
        decision.put("agentId","reviewer");when(processes.variables("child")).thenReturn(new HashMap<String,Object>(){{put("agent_session_id","reviewer");put("authorization_id",id);}});
        assertEquals(true,service.decide(decision).get("authorizationApproved"));service.requireApproved(id,"parent");
        parent.put("payload","{\"selected_card_id\":\"CARD\",\"id_front_photo\":\"/records/replaced.png\"}");
        assertThrows(ResponseStatusException.class,()->service.requireApproved(id,"parent"));
    }
}
