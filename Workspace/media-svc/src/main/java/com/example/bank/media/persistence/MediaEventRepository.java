package com.example.bank.media.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 视频相关流水/事件（media_session_event，append-only）。 */
@Repository
public class MediaEventRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MediaEventRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS media_session_event ("
                + "event_id VARCHAR(64) NOT NULL,"
                + "room_id VARCHAR(64) NOT NULL,"
                + "user_id VARCHAR(64) DEFAULT NULL,"
                + "recording_id VARCHAR(64) DEFAULT NULL,"
                + "event_type VARCHAR(48) NOT NULL,"
                + "occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "duration_ms BIGINT NOT NULL DEFAULT 0,"
                + "payload_json MEDIUMTEXT DEFAULT NULL,"
                + "platform_event_id VARCHAR(128) DEFAULT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (event_id),"
                + "UNIQUE (platform_event_id),"
                + "KEY idx_event_room (room_id, occurred_at),"
                + "KEY idx_event_recording (recording_id, occurred_at)"
                + ")");
    }

    public Map<String, Object> record(String roomId, String userId, String recordingId,
                                      String eventType, long durationMs,
                                      Map<String, Object> payload, String platformEventId) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("roomId", roomId);
        event.put("userId", userId);
        event.put("recordingId", recordingId);
        event.put("type", eventType);
        event.put("occurredAt", System.currentTimeMillis());
        if (payload != null) event.putAll(payload);
        jdbc.update("INSERT INTO media_session_event"
                        + "(event_id,room_id,user_id,recording_id,event_type,duration_ms,payload_json,platform_event_id) "
                        + "VALUES(?,?,?,?,?,?,?,?)",
                event.get("eventId"), roomId, userId, recordingId, eventType, durationMs,
                encode(event), platformEventId);
        return event;
    }

    public List<Map<String, Object>> listByRoom(String roomId) {
        return jdbc.queryForList(
                "SELECT * FROM media_session_event WHERE room_id=? ORDER BY occurred_at, event_id", roomId);
    }

    public List<Map<String, Object>> listByRecording(String recordingId) {
        return jdbc.queryForList(
                "SELECT * FROM media_session_event WHERE recording_id=? ORDER BY occurred_at, event_id",
                recordingId);
    }

    private String encode(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("媒体事件序列化失败", error);
        }
    }
}
