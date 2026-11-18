package com.example.bank.message.web;

import com.example.bank.message.persistence.MessageRecordRepository;
import com.example.bank.message.persistence.MessageSchemaInitializer;
import com.example.bank.message.service.MessageDeliveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventDeliveryControllerTest {

    private JdbcTemplate jdbc() {
        String url = "jdbc:h2:mem:message-delivery-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        new MessageSchemaInitializer(jdbc).initialize();
        return jdbc;
    }

    private EventDeliveryController controller(RestTemplate http, JdbcTemplate jdbc) {
        MessageRecordRepository records = new MessageRecordRepository(jdbc);
        return new EventDeliveryController(new MessageDeliveryService(http, "http://signal", records, new ObjectMapper()));
    }

    private Map<String, Object> event() {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", "NODE_STATE");
        event.put("eventId", "E1");
        return event;
    }

    @Test
    void transportRefusalIsNotReportedAsDeliveredAndIsPersistedAsFailed() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForObject(anyString(), anyMap(), eq(Map.class), eq("customer"), eq("c1")))
                .thenReturn(Collections.singletonMap("delivered", false));
        JdbcTemplate jdbc = jdbc();
        EventDeliveryController controller = controller(http, jdbc);

        assertThrows(ResponseStatusException.class,
                () -> controller.deliver("customer", "c1", event()));

        Map<String, Object> row = new MessageRecordRepository(jdbc).find("E1:customer:c1");
        assertEquals("FAILED", row.get("status"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());
        assertTrue(String.valueOf(row.get("last_error")).contains("消息尚未交付信令服务"));
    }

    @Test
    void invalidAudienceCannotBecomeBroadcast() {
        RestTemplate http = mock(RestTemplate.class);
        JdbcTemplate jdbc = jdbc();
        EventDeliveryController controller = controller(http, jdbc);

        assertThrows(ResponseStatusException.class,
                () -> controller.deliver("all", "c1", event()));
        verifyNoInteractions(http);
    }
}
