package com.example.bank.business.application;

import com.example.bank.business.controller.PresenceController;
import com.example.bank.business.port.AgentPort;
import com.example.bank.business.port.SignalPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ProductEventServiceTest {

    private AgentPort agents;
    private SignalPort signals;
    private ProductEventService events;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:product-event-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        agents = mock(AgentPort.class);
        signals = mock(SignalPort.class);
        events = new ProductEventService(jdbc, new PresenceController(agents, 45000L, 15000L), signals);
        events.initialize();
    }

    @Test
    void connectionClosedForAgentBecomesOfflineAndDuplicateIsIgnored() {
        Map<String, Object> event = event("e1", "AGENT");
        Map<String, Object> first = events.handle("signaling", event);
        Map<String, Object> second = events.handle("signaling", event);

        assertEquals(true, first.get("accepted"));
        assertEquals(false, first.get("duplicate"));
        assertEquals(true, second.get("duplicate"));
        verify(agents, times(1)).offline("a1");
    }

    @Test
    void customerConnectionClosedHasNoBusinessMeaning() {
        events.handle("signaling", event("e2", "CUSTOMER"));
        verify(agents, times(0)).offline("a1");
    }

    @Test
    void unsupportedProductEventIsRejected() {
        Map<String, Object> event = event("e3", "AGENT");
        event.put("type", "SOMETHING_ELSE");
        assertThrows(ResponseStatusException.class, () -> events.handle("signaling", event));
    }

    @Test
    void routingRingingIsPushedToAgentAndDuplicateIsIgnored() {
        Map<String, Object> event = routingEvent("r1", "TICKET_RINGING", "a1");
        events.handle("routing", event);
        events.handle("routing", event);

        verify(signals, times(1)).publishAgent(eq("a1"),
                argThat(push -> "CALL_RINGING".equals(push.get("type")) && "T1".equals(
                        ((Map<?, ?>) push.get("ticket")).get("ticketId"))));
    }

    @Test
    void mediaRoomReadyIsPushedToCustomerAndAgent() {
        Map<String, Object> event = mediaEvent("m1", "ROOM_READY");
        events.handle("media", event);

        verify(signals).publishCustomer(eq("c1"),
                argThat(push -> "VIDEO_ROOM_READY".equals(push.get("type"))));
        verify(signals).publishAgent(eq("a1"),
                argThat(push -> "VIDEO_ROOM_READY".equals(push.get("type"))));
    }

    private Map<String, Object> mediaEvent(String eventId, String type) {
        Map<String, Object> push = new LinkedHashMap<String, Object>();
        push.put("type", "VIDEO_ROOM_READY");
        push.put("roomId", "R1");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("roomId", "R1");
        payload.put("customerId", "c1");
        payload.put("agentId", "a1");
        payload.put("actorId", "c1");
        payload.put("role", "CUSTOMER");
        payload.put("event", push);

        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("eventId", eventId);
        event.put("type", type);
        event.put("occurredAt", 1000L);
        event.put("payload", payload);
        return event;
    }

    private Map<String, Object> routingEvent(String eventId, String type, String agentId) {
        Map<String, Object> ticket = new LinkedHashMap<String, Object>();
        ticket.put("ticketId", "T1");
        ticket.put("processId", "p1");
        ticket.put("bizKey", "BIZ-1");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("agentId", agentId);
        payload.put("ticket", ticket);
        payload.put("at", 1000L);

        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("eventId", eventId);
        event.put("type", type);
        event.put("occurredAt", 1000L);
        event.put("payload", payload);
        return event;
    }

    private Map<String, Object> event(String eventId, String role) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("subjectId", "a1");
        payload.put("role", role);
        payload.put("channel", "agent:a1");
        payload.put("reason", "PRESENCE_EMPTY");
        payload.put("source", "PRESENCE_SWEEP");
        payload.put("at", 1000L);

        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("eventId", eventId);
        event.put("type", "CONNECTION_CLOSED");
        event.put("occurredAt", 1000L);
        event.put("payload", payload);
        return event;
    }
}
