package com.example.bank.routing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JdbcProductEventPublisherTest {

    private JdbcTemplate jdbc() {
        String url = "jdbc:h2:mem:routing-outbox-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        return new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    }

    @Test
    void publishesPendingEventToBusiness() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://localhost:8080/internal/events/routing"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("TICKET_RINGING"))
                .andExpect(jsonPath("$.payload.agentId").value("a1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        JdbcTemplate jdbc = jdbc();
        JdbcProductEventPublisher publisher = new JdbcProductEventPublisher(
                jdbc, rest, new ObjectMapper(), "http://localhost:8080");
        publisher.initialize();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("agentId", "a1");
        publisher.publish("TICKET_RINGING", payload);
        publisher.publishPending();
        server.verify();

        assertEquals(1, jdbc.queryForObject(
                "SELECT delivered FROM routing_event_outbox", Integer.class));
    }

    @Test
    void failedDeliveryKeepsEventForRetry() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://localhost:8080/internal/events/routing"))
                .andRespond(withServerError());

        JdbcTemplate jdbc = jdbc();
        JdbcProductEventPublisher publisher = new JdbcProductEventPublisher(
                jdbc, rest, new ObjectMapper(), "http://localhost:8080");
        publisher.initialize();
        publisher.publish("QUEUE_UPDATED", new LinkedHashMap<String, Object>());
        publisher.publishPending();
        server.verify();

        Map<String, Object> row = jdbc.queryForMap("SELECT delivered, attempts FROM routing_event_outbox");
        assertEquals(0, ((Number) row.get("delivered")).intValue());
        assertEquals(1, ((Number) row.get("attempts")).intValue());
    }
}
