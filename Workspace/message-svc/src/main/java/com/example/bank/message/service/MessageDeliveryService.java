package com.example.bank.message.service;

import com.example.bank.message.persistence.MessageRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 消息投递：先落消息记录（ACCEPTED），再调信令服务投递。
 *
 * <p>投递成功写 DELIVERED + delivered_at；失败写 FAILED + last_error，并把原始异常
 * 继续上抛，保持现有 `/internal/delivery` 的 503 语义。消息记录是投递事实，
 * 后续可按 subject/status 查询，也可对失败记录重试。</p>
 */
@Service
public class MessageDeliveryService {

    private final RestTemplate http;
    private final String signaling;
    private final MessageRecordRepository records;
    private final ObjectMapper json;

    public MessageDeliveryService(RestTemplate http,
                                  @Value("${message.signaling-base-url:http://localhost:8082}") String signaling,
                                  MessageRecordRepository records,
                                  ObjectMapper json) {
        this.http = http;
        this.signaling = signaling;
        this.records = records;
        this.json = json;
    }

    /** 受理并投递一条消息；成功后返回 `{delivered:true,eventId}`。 */
    public Map<String, Object> deliver(String audience, String subject, Map<String, Object> event) {
        validate(audience, subject, event);
        String eventId = event.get("eventId") == null ? null : String.valueOf(event.get("eventId"));
        String eventType = String.valueOf(event.get("type"));
        String payload = payload(event);
        String messageId = messageId(eventId, audience, subject);

        records.accepted(messageId, eventId, audience, subject, eventType, payload);
        try {
            publish(audience, subject, event);
            records.delivered(messageId);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("delivered", true);
            result.put("eventId", event.get("eventId"));
            return result;
        } catch (RuntimeException error) {
            markFailed(messageId, error);
            throw error;
        }
    }

    /** 按 messageId 重试一条失败消息；原事件信封从记录中恢复。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> retry(String messageId) {
        Map<String, Object> row = records.find(messageId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在：" + messageId);
        }
        String audience = String.valueOf(row.get("audience"));
        String subject = String.valueOf(row.get("subject_id"));
        String eventId = row.get("event_id") == null ? null : String.valueOf(row.get("event_id"));
        String eventType = row.get("event_type") == null ? null : String.valueOf(row.get("event_type"));
        String payload = String.valueOf(row.get("payload_json"));
        Map<String, Object> event;
        try {
            event = json.readValue(payload, Map.class);
        } catch (Exception error) {
            throw new IllegalStateException("消息事件解析失败：" + messageId, error);
        }

        records.accepted(messageId, eventId, audience, subject, eventType, payload);
        try {
            publish(audience, subject, event);
            records.delivered(messageId);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("delivered", true);
            result.put("messageId", messageId);
            result.put("eventId", eventId);
            return result;
        } catch (RuntimeException error) {
            markFailed(messageId, error);
            throw error;
        }
    }

    private void publish(String audience, String subject, Map<String, Object> event) {
        Map<?, ?> receipt = http.postForObject(
                signaling + "/internal/{audience}/{subject}/publish", event, Map.class, audience, subject);
        if (receipt == null || !Boolean.TRUE.equals(receipt.get("delivered"))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "消息尚未交付信令服务");
        }
    }

    private void validate(String audience, String subject, Map<String, Object> event) {
        if (!Arrays.asList("agent", "customer").contains(audience)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知目标缺失");
        }
        if (subject == null || subject.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知目标缺失");
        }
        if (event == null || event.get("type") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知目标或事件类型缺失");
        }
    }

    private String payload(Map<String, Object> event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception error) {
            throw new IllegalStateException("消息事件序列化失败", error);
        }
    }

    private String messageId(String eventId, String audience, String subject) {
        if (eventId != null && !eventId.trim().isEmpty()) {
            return eventId.trim() + ":" + audience + ":" + subject;
        }
        return UUID.randomUUID().toString();
    }

    private void markFailed(String messageId, RuntimeException original) {
        try {
            records.failed(messageId, String.valueOf(original.getMessage()));
        } catch (RuntimeException persistenceError) {
            original.addSuppressed(persistenceError);
        }
    }
}
