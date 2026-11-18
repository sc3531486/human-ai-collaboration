package com.example.bank.message.web;

import com.example.bank.message.persistence.MessageRecordRepository;
import com.example.bank.message.persistence.MessageTemplateRepository;
import com.example.bank.message.service.MessageDeliveryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** 消息产品查询/重试与模板管理接口。只读写 message-svc 自己的表。 */
@RestController
@RequestMapping("/internal")
public class MessageQueryController {

    private final MessageRecordRepository records;
    private final MessageTemplateRepository templates;
    private final MessageDeliveryService delivery;

    public MessageQueryController(MessageRecordRepository records,
                                  MessageTemplateRepository templates,
                                  MessageDeliveryService delivery) {
        this.records = records;
        this.templates = templates;
        this.delivery = delivery;
    }

    @GetMapping("/messages/{messageId}")
    public ResponseEntity<Map<String, Object>> message(@PathVariable String messageId) {
        Map<String, Object> row = records.find(messageId);
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(records.view(row));
    }

    @GetMapping("/messages")
    public List<Map<String, Object>> messages(@RequestParam(required = false) String subject,
                                              @RequestParam(required = false) String status) {
        return records.list(subject, status);
    }

    @PostMapping("/messages/{messageId}/retry")
    public Map<String, Object> retry(@PathVariable String messageId) {
        return delivery.retry(messageId);
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return templates.list();
    }

    @GetMapping("/templates/{code}")
    public ResponseEntity<Map<String, Object>> template(@PathVariable String code) {
        Map<String, Object> row = templates.find(code);
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(row);
    }

    @PostMapping("/templates")
    public Map<String, Object> saveTemplate(@RequestBody Map<String, Object> body) {
        String code = text(body.get("templateCode"));
        String name = text(body.get("templateName"));
        String content = text(body.get("contentTemplate"));
        if (code.isEmpty() || name.isEmpty() || content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板编码、名称和内容不能为空");
        }
        String eventType = nullable(body.get("eventType"));
        String title = nullable(body.get("title"));
        boolean enabled = true;
        Object rawEnabled = body.get("enabled");
        if (rawEnabled != null) {
            String text = String.valueOf(rawEnabled).trim();
            enabled = "1".equals(text) || "true".equalsIgnoreCase(text);
        }
        templates.save(code, name, eventType, title, content, enabled);
        return templates.find(code);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String nullable(Object value) {
        String text = text(value);
        return text.isEmpty() ? null : text;
    }
}
