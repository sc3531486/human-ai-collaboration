package com.example.bank.routing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 工单事件流水仓储：append-only，不覆盖历史。 */
@Service
public class RoutingTicketEventRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RoutingTicketEventRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(String ticketId, String eventType, String agentId, Map<String, Object> payload) {
        String encoded = null;
        if (payload != null) {
            try {
                encoded = json.writeValueAsString(payload);
            } catch (Exception error) {
                throw new IllegalStateException("工单事件载荷序列化失败，ticketId=" + ticketId, error);
            }
        }
        jdbc.update("INSERT INTO routing_ticket_event(ticket_id, event_type, agent_id, occurred_at, payload_json) "
                + "VALUES(?,?,?,CURRENT_TIMESTAMP,?)", ticketId, eventType, agentId, encoded);
    }

    public List<Map<String, Object>> forTicket(String ticketId) {
        return jdbc.queryForList(
                "SELECT * FROM routing_ticket_event WHERE ticket_id=? ORDER BY event_id", ticketId);
    }
}
