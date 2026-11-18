package com.example.bank.business.application;

import com.example.bank.business.controller.PresenceController;
import com.example.bank.business.port.SignalPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品事件入口：产品只上报通用领域事实，business 决定其业务含义。
 *
 * <p>已接入：</p>
 * <ul>
 *   <li>signaling {@code CONNECTION_CLOSED}：连接没了 → 坐席离线；</li>
 *   <li>routing {@code TICKET_RINGING/TICKET_ANSWERED/QUEUE_UPDATED}：
 *       换出业务对象后经信令推送，路由不再直连信令。</li>
 * </ul>
 *
 * <p>按 {@code eventId} 幂等；推送类事件先推送再登记，推送失败时产品 outbox 会重试。</p>
 */
@Service
public class ProductEventService {

    private final JdbcTemplate jdbc;
    private final PresenceController presence;
    private final SignalPort signals;

    public ProductEventService(JdbcTemplate jdbc, PresenceController presence, SignalPort signals) {
        this.jdbc = jdbc;
        this.presence = presence;
        this.signals = signals;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS product_event_inbox ("
                + "event_id VARCHAR(160) NOT NULL COMMENT '事件ID',"
                + "product VARCHAR(32) NOT NULL COMMENT '来源产品',"
                + "event_type VARCHAR(64) NOT NULL COMMENT '事件类型',"
                + "received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',"
                + "PRIMARY KEY (event_id)"
                + ")");
    }

    public Map<String, Object> handle(String product, Map<String, Object> event) {
        if (event == null) throw bad("缺少事件体");
        String eventId = text(event.get("eventId"));
        String type = text(event.get("type"));
        if (eventId.isEmpty() || type.isEmpty()) throw bad("事件缺少 eventId 或 type");

        if ("signaling".equals(product)) return handleSignaling(eventId, type, event);
        if ("routing".equals(product)) return handleRouting(eventId, type, event);
        if ("media".equals(product)) return handleMedia(eventId, type, event);
        throw bad("不支持的产品事件来源：" + product);
    }

    private Map<String, Object> handleSignaling(String eventId, String type, Map<String, Object> event) {
        if (!"CONNECTION_CLOSED".equals(type)) throw bad("不支持的信令事件类型：" + type);
        Map<String, Object> payload = payload(event.get("payload"));
        String subjectId = text(payload.get("subjectId"));
        String role = text(payload.get("role"));
        if (subjectId.isEmpty()) throw bad("CONNECTION_CLOSED 缺少 subjectId");

        if (processed(eventId)) return result(true);
        // 客户连接消失当前没有业务含义；只有坐席连接消失才翻译成离线。
        if ("AGENT".equals(role)) {
            presence.onAgentDisconnected(subjectId, millis(payload.get("at"), event.get("occurredAt")),
                    text(payload.get("reason")), text(payload.get("source")));
        }
        mark(eventId, "signaling", type);
        return result(false);
    }

    private Map<String, Object> handleRouting(String eventId, String type, Map<String, Object> event) {
        if (!"TICKET_RINGING".equals(type) && !"TICKET_ANSWERED".equals(type) && !"QUEUE_UPDATED".equals(type)) {
            throw bad("不支持的路由事件类型：" + type);
        }
        if (processed(eventId)) return result(true);

        Map<String, Object> payload = payload(event.get("payload"));
        long at = millis(payload.get("at"), event.get("occurredAt"));

        if ("QUEUE_UPDATED".equals(type)) {
            Object raw = payload.get("agentIds");
            if (!(raw instanceof List)) throw bad("QUEUE_UPDATED 缺少 agentIds");
            Map<String, Object> push = new LinkedHashMap<String, Object>();
            push.put("domain", "call");
            push.put("type", "QUEUE_UPDATED");
            push.put("at", at);
            push.put("eventId", eventId);
            for (Object agentId : (List<?>) raw) {
                String id = text(agentId);
                if (!id.isEmpty()) signals.publishAgent(id, push);
            }
        } else {
            String agentId = text(payload.get("agentId"));
            if (agentId.isEmpty()) throw bad(type + " 缺少 agentId");
            Map<String, Object> push = new LinkedHashMap<String, Object>();
            push.put("domain", "call");
            push.put("type", "TICKET_RINGING".equals(type) ? "CALL_RINGING" : "CALL_ANSWERED");
            push.put("at", at);
            push.put("eventId", eventId);
            push.put("ticket", payload.get("ticket"));
            if ("TICKET_ANSWERED".equals(type)) push.put("agentId", agentId);
            signals.publishAgent(agentId, push);
        }
        mark(eventId, "routing", type);
        return result(false);
    }

    private Map<String, Object> handleMedia(String eventId, String type, Map<String, Object> event) {
        if (!"ROOM_READY".equals(type) && !"CALL_ENDED".equals(type)
                && !"VIDEO_ROOM_READY".equals(type) && !"ROOM_PARTICIPANTS_CHANGED".equals(type)
                && !"PARTICIPANT_RELEASED".equals(type)) {
            throw bad("不支持的媒体事件类型：" + type);
        }
        if (processed(eventId)) return result(true);

        Map<String, Object> payload = payload(event.get("payload"));
        Object rawEvent = payload.get("event");
        if (!(rawEvent instanceof Map)) throw bad("媒体事件缺少 event 载荷");
        @SuppressWarnings("unchecked")
        Map<String, Object> push = (Map<String, Object>) rawEvent;

        if ("ROOM_READY".equals(type)) {
            String customerId = text(payload.get("customerId"));
            String agentId = text(payload.get("agentId"));
            if (customerId.isEmpty() || agentId.isEmpty()) throw bad("ROOM_READY 缺少 customerId 或 agentId");
            signals.publishCustomer(customerId, push);
            signals.publishAgent(agentId, push);
        } else {
            String actorId = text(payload.get("actorId"));
            String role = text(payload.get("role"));
            if (actorId.isEmpty()) throw bad(type + " 缺少 actorId");
            if ("CUSTOMER".equals(role)) {
                signals.publishCustomer(actorId, push);
            } else {
                signals.publishAgent(actorId, push);
            }
        }
        mark(eventId, "media", type);
        return result(false);
    }

    private boolean processed(String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_event_inbox WHERE event_id=?", Integer.class, eventId);
        return count != null && count > 0;
    }

    private void mark(String eventId, String product, String type) {
        try {
            jdbc.update("INSERT INTO product_event_inbox(event_id, product, event_type) VALUES(?,?,?)",
                    eventId, product, type);
        } catch (DuplicateKeyException ignored) {
            // 并发重复投递：业务动作已幂等，登记冲突可忽略
        }
    }

    private Map<String, Object> result(boolean duplicate) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accepted", true);
        result.put("duplicate", duplicate);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Object raw) {
        if (raw instanceof Map) return (Map<String, Object>) raw;
        return new LinkedHashMap<String, Object>();
    }

    private long millis(Object payloadAt, Object occurredAt) {
        Object value = payloadAt != null ? payloadAt : occurredAt;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 兼容 ISO-8601 时间戳
                try {
                    return java.time.Instant.parse(String.valueOf(value).trim()).toEpochMilli();
                } catch (RuntimeException error) {
                    throw bad("事件时间戳格式非法：" + value);
                }
            }
        }
        throw bad("事件缺少时间戳");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
