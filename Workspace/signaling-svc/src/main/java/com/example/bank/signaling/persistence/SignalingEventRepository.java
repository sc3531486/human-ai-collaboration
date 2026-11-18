package com.example.bank.signaling.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

/** 信令领域事件流水（append-only）。 */
@Repository
public class SignalingEventRepository {

    private final JdbcTemplate jdbc;

    public SignalingEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String eventType, String subjectId, String connectionId, long at, String payloadJson) {
        jdbc.update("INSERT INTO signaling_event(event_id, subject_id, connection_id, event_type, occurred_at, payload_json) "
                        + "VALUES(?,?,?,?,?,?)",
                "EVT-" + UUID.randomUUID().toString().replace("-", ""),
                subjectId, connectionId, eventType, new Timestamp(at), payloadJson);
    }
}
