package com.example.bank.message;

import com.example.bank.message.persistence.MessageRecordRepository;
import com.example.bank.message.persistence.MessageSchemaInitializer;
import com.example.bank.message.persistence.MessageTemplateRepository;
import com.example.bank.message.service.MessageDeliveryService;
import com.example.bank.message.web.MessageQueryController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagePersistenceTest {

    private JdbcTemplate jdbc() {
        String url = "jdbc:h2:mem:message-persist-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        new MessageSchemaInitializer(jdbc).initialize();
        return jdbc;
    }

    private Map<String, Object> event(String eventId, String type) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("eventId", eventId);
        event.put("type", type);
        return event;
    }

    @Test
    void deliveredMessageIsPersistedAndQueryable() {
        JdbcTemplate jdbc = jdbc();
        MessageRecordRepository records = new MessageRecordRepository(jdbc);
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForObject(anyString(), anyMap(), eq(Map.class), eq("agent"), eq("a1")))
                .thenReturn(Collections.singletonMap("delivered", true));
        MessageDeliveryService service = new MessageDeliveryService(http, "http://signal", records, new ObjectMapper());

        Map<String, Object> result = service.deliver("agent", "a1", event("E2", "CALL_RINGING"));

        assertEquals(Boolean.TRUE, result.get("delivered"));
        Map<String, Object> row = records.find("E2:agent:a1");
        assertEquals("DELIVERED", row.get("status"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());
        assertNotNull(row.get("delivered_at"));
        assertEquals(1, records.list("a1", "DELIVERED").size());
    }

    @Test
    void failedMessageCanBeRetriedWithStoredPayload() {
        JdbcTemplate jdbc = jdbc();
        MessageRecordRepository records = new MessageRecordRepository(jdbc);
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForObject(anyString(), anyMap(), eq(Map.class), eq("customer"), eq("c1")))
                .thenReturn(Collections.singletonMap("delivered", false),
                        Collections.singletonMap("delivered", true));
        MessageDeliveryService service = new MessageDeliveryService(http, "http://signal", records, new ObjectMapper());

        assertThrows(ResponseStatusException.class,
                () -> service.deliver("customer", "c1", event("E3", "NODE_STATE")));
        assertEquals("FAILED", records.find("E3:customer:c1").get("status"));

        Map<String, Object> retried = service.retry("E3:customer:c1");

        assertEquals(Boolean.TRUE, retried.get("delivered"));
        Map<String, Object> row = records.find("E3:customer:c1");
        assertEquals("DELIVERED", row.get("status"));
        assertEquals(2, ((Number) row.get("attempts")).intValue());
    }

    @Test
    void templatesUpsertAndList() {
        JdbcTemplate jdbc = jdbc();
        MessageTemplateRepository templates = new MessageTemplateRepository(jdbc);
        templates.save("T1", "节点通知", "NODE_STATE", "节点提醒", "当前节点：${nodeName}", true);
        templates.save("T1", "节点通知-新", "NODE_STATE", "节点提醒", "当前节点：${nodeName}", true);

        List<Map<String, Object>> rows = templates.list();
        assertEquals(1, rows.size());
        assertEquals("节点通知-新", templates.find("T1").get("template_name"));
    }

    @Test
    void queryControllerExposesMessageAndTemplates() {
        JdbcTemplate jdbc = jdbc();
        MessageRecordRepository records = new MessageRecordRepository(jdbc);
        MessageTemplateRepository templates = new MessageTemplateRepository(jdbc);
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForObject(anyString(), anyMap(), eq(Map.class), eq("agent"), eq("a2")))
                .thenReturn(Collections.singletonMap("delivered", true));
        MessageDeliveryService service = new MessageDeliveryService(http, "http://signal", records, new ObjectMapper());
        service.deliver("agent", "a2", event("E4", "CALL_RINGING"));
        templates.save("T2", "模板", null, null, "内容", true);

        MessageQueryController controller = new MessageQueryController(records, templates, service);

        assertEquals("DELIVERED", controller.message("E4:agent:a2").getBody().get("status"));
        assertEquals(1, controller.messages("a2", "DELIVERED").size());
        assertEquals(1, controller.templates().size());
        assertEquals("模板", controller.template("T2").getBody().get("template_name"));
    }
}
