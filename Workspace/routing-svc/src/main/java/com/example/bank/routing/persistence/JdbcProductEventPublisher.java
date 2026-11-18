package com.example.bank.routing.persistence;

import com.example.bank.routing.port.ProductEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 路由事件 outbox：先落库、再异步投递 business。
 *
 * <p>振铃/接听/队列变更不能因为 business 一时不可达而丢失；事件表按
 * {@code event_id} 幂等，business 侧按同一 ID 去重。</p>
 */
@Component
public class JdbcProductEventPublisher implements ProductEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductEventPublisher.class);

    private final JdbcTemplate jdbc;
    private final RestTemplate http;
    private final ObjectMapper json;
    private final String businessBaseUrl;

    public JdbcProductEventPublisher(JdbcTemplate jdbc, RestTemplate http, ObjectMapper json,
                                     @Value("${routing.business-base-url}") String businessBaseUrl) {
        this.jdbc = jdbc;
        this.http = http;
        this.json = json;
        this.businessBaseUrl = businessBaseUrl;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS routing_event_outbox ("
                + "event_id VARCHAR(64) NOT NULL,"
                + "event_type VARCHAR(48) NOT NULL,"
                + "payload_json MEDIUMTEXT NOT NULL,"
                + "delivered TINYINT NOT NULL DEFAULT 0,"
                + "attempts INT NOT NULL DEFAULT 0,"
                + "last_error VARCHAR(500) DEFAULT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (event_id),"
                + "KEY idx_outbox_pending (delivered, created_at)"
                + ")");
    }

    @Override
    public void publish(String type, Map<String, Object> payload) {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("路由事件缺少类型");
        }
        try {
            jdbc.update("INSERT INTO routing_event_outbox(event_id, event_type, payload_json) VALUES(?,?,?)",
                    UUID.randomUUID().toString(), type, json.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("路由事件序列化失败", error);
        }
    }

    @Scheduled(fixedDelayString = "${routing.event-publish-ms:750}")
    public void publishPending() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, event_type, payload_json, created_at FROM routing_event_outbox "
                        + "WHERE delivered=0 ORDER BY created_at, event_id LIMIT 50");
        for (Map<String, Object> row : rows) {
            String eventId = String.valueOf(row.get("event_id"));
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = json.readValue(String.valueOf(row.get("payload_json")), Map.class);
                Map<String, Object> event = new LinkedHashMap<String, Object>();
                event.put("eventId", eventId);
                event.put("type", String.valueOf(row.get("event_type")));
                event.put("occurredAt", JdbcTimes.millis(row.get("created_at")));
                event.put("payload", payload);
                http.postForObject(businessBaseUrl + "/internal/events/routing", event, Map.class);
                jdbc.update("UPDATE routing_event_outbox SET delivered=1, updated_at=CURRENT_TIMESTAMP WHERE event_id=?", eventId);
            } catch (Exception error) {
                jdbc.update("UPDATE routing_event_outbox SET attempts=attempts+1, last_error=?, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE event_id=?", truncate(error.getMessage()), eventId);
                log.warn("路由事件待重试 eventId={} type={}", eventId, row.get("event_type"));
            }
        }
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
