package com.example.bank.business.service;

import com.example.bank.business.application.CallSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentServiceSummaryServiceTest {
    private AgentServiceSummaryService summaries;
    private CallSessionService calls;
    private ServiceBusinessCatalog catalog;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:service-summary-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        calls = mock(CallSessionService.class);
        jdbc.execute("CREATE TABLE biz_record(biz_no VARCHAR(64) PRIMARY KEY,process_instance_id VARCHAR(64),trade_code VARCHAR(64),trade_name VARCHAR(120),status VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_call_session(call_id VARCHAR(64) PRIMARY KEY)");
        jdbc.update("INSERT INTO biz_call_session VALUES('CALL-1')");
        jdbc.update("INSERT INTO biz_record VALUES('BIZ-1','PROC-1','200101','密码重置','COMPLETED')");
        jdbc.update("INSERT INTO biz_record VALUES('BIZ-2','PROC-2','200102','账户查询','PROCESSING')");
        jdbc.update("INSERT INTO biz_record VALUES('OTHER','PROC-X','300101','其他客户转账','COMPLETED')");
        catalog = new ServiceBusinessCatalog(jdbc);catalog.initialize();catalog.attach("CALL-1","PROC-2","BIZ-2");
        summaries = new AgentServiceSummaryService(jdbc, calls, new ObjectMapper(),catalog);
        summaries.initialize();
        Map<String, Object> call = new HashMap<>();
        call.put("call_id", "CALL-1"); call.put("agent_id", "AGENT-9527"); call.put("customer_id", "CUSTOMER-1");
        call.put("status", "ENDED"); call.put("biz_no", "BIZ-1"); call.put("process_id", "PROC-1");
        call.put("context_json", "{\"customer_name\":\"测试用户\",\"channel\":\"企业网银\",\"customer_mobile_masked\":\"138****1234\",\"trade_name\":\"密码重置\"}");
        when(calls.requiredCall("CALL-1")).thenReturn(call);
    }

    @Test
    void storesHeaderAndMultipleBusinessRowsForOneService() {
        Map<String, Object> input = new HashMap<>();
        input.put("callId", "CALL-1"); input.put("agentId", "AGENT-9527"); input.put("labels", Arrays.asList("资料核验完成", "客户沟通顺畅")); input.put("remark", "客户已确认办理结果");
        List<Map<String, Object>> businesses = new ArrayList<>();
        businesses.add(business("BIZ-1", "密码重置")); businesses.add(business("BIZ-2", "账户查询")); input.put("businesses", businesses);
        Map<String, Object> saved = summaries.submit(input);
        assertEquals("测试用户", saved.get("customerName"));
        assertEquals(2, ((List<?>) saved.get("businesses")).size());
        assertEquals(2, ((List<?>) saved.get("labels")).size());
        assertEquals("企业网银",saved.get("incomingChannel"));
        assertEquals("138****1234",summaries.draft("CALL-1","AGENT-9527").get("customerMobileMasked"));
        assertEquals("PROCESSING",((Map<?,?>)((List<?>)saved.get("businesses")).get(1)).get("handlingResult"));
    }

    @Test void unrelatedBusinessCannotBeAddedToSummary() {
        Map<String,Object> input=new HashMap<>();input.put("callId","CALL-1");input.put("agentId","AGENT-9527");input.put("businesses",Collections.singletonList(business("OTHER","伪造业务")));
        assertThrows(ResponseStatusException.class,()->summaries.submit(input));
    }

    @Test void draftDoesNotClaimEveryBusinessIsCompleted() {
        List<?> rows=(List<?>)summaries.draft("CALL-1","AGENT-9527").get("businesses");
        assertEquals(2,rows.size());assertEquals("PROCESSING",((Map<?,?>)rows.get(1)).get("handlingResult"));
    }

    private Map<String, Object> business(String bizNo, String name) {
        Map<String, Object> value = new HashMap<>();
        value.put("bizNo", bizNo); value.put("tradeName", name); value.put("handlingResult", "COMPLETED"); return value;
    }
}
