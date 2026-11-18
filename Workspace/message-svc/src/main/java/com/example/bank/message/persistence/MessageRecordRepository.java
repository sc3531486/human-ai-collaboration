package com.example.bank.message.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 消息投递记录的读写。状态只由投递流程推进：ACCEPTED → DELIVERED / FAILED。 */
@Repository
public class MessageRecordRepository {

    private final JdbcTemplate jdbc;

    public MessageRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 受理一条消息；同一 messageId 重试时累加 attempts 并回到 ACCEPTED。 */
    public void accepted(String messageId, String eventId, String audience, String subject,
                         String eventType, String payloadJson) {
        int updated = jdbc.update(
                "UPDATE message_record SET event_id=?, audience=?, subject_id=?, event_type=?, "
                        + "payload_json=?, status='ACCEPTED', attempts=attempts+1, last_error=NULL, "
                        + "updated_at=CURRENT_TIMESTAMP WHERE message_id=?",
                eventId, audience, subject, eventType, payloadJson, messageId);
        if (updated == 0) {
            jdbc.update("INSERT INTO message_record(message_id, event_id, audience, subject_id, event_type, "
                            + "payload_json, status, attempts) VALUES(?,?,?,?,?,?, 'ACCEPTED', 1)",
                    messageId, eventId, audience, subject, eventType, payloadJson);
        }
    }

    public void delivered(String messageId) {
        jdbc.update("UPDATE message_record SET status='DELIVERED', delivered_at=CURRENT_TIMESTAMP, "
                + "last_error=NULL, updated_at=CURRENT_TIMESTAMP WHERE message_id=?", messageId);
    }

    public void failed(String messageId, String error) {
        jdbc.update("UPDATE message_record SET status='FAILED', last_error=?, updated_at=CURRENT_TIMESTAMP "
                + "WHERE message_id=?", truncate(error), messageId);
    }

    public Map<String, Object> find(String messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM message_record WHERE message_id=?", messageId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> list(String subject, String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM message_record WHERE 1=1");
        java.util.List<Object> params = new java.util.ArrayList<Object>();
        if (subject != null && !subject.trim().isEmpty()) {
            sql.append(" AND subject_id=?");
            params.add(subject.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            sql.append(" AND status=?");
            params.add(status.trim());
        }
        sql.append(" ORDER BY accepted_at DESC, message_id DESC LIMIT 200");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            result.add(view(row));
        }
        return result;
    }

    /** 统一输出小写字段名，避免 H2/MySQL 列名大小写差异泄漏到接口。 */
    public Map<String, Object> view(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            view.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        Object payload = view.get("payload_json");
        if (payload != null) {
            String text = String.valueOf(payload);
            view.put("payload", text.length() > 2000 ? text.substring(0, 2000) + "..." : text);
        }
        return view;
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
