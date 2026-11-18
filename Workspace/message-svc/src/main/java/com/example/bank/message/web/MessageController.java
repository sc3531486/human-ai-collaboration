package com.example.bank.message.web;

import com.example.bank.message.auth.MessageTokenService;
import com.example.bank.message.config.MessageProperties;
import com.example.bank.message.presence.ConnectionRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/internal")
public class MessageController {

    private final SimpMessagingTemplate messaging;
    private final MessageProperties properties;
    private final MessageTokenService tokenService;
    private final ConnectionRegistry registry;

    public MessageController(SimpMessagingTemplate messaging,
                             MessageProperties properties,
                             MessageTokenService tokenService,
                             ConnectionRegistry registry) {
        this.messaging = messaging;
        this.properties = properties;
        this.tokenService = tokenService;
        this.registry = registry;
    }

    @PostMapping("/agent/{agentId}/publish")
    public Map<String, Object> publishAgent(@PathVariable String agentId,
                                            @RequestBody Map<String, Object> event) {
        return publish(agentId, "agent:" + agentId, event);
    }

    @PostMapping("/customer/{customerId}/publish")
    public Map<String, Object> publishCustomer(@PathVariable String customerId,
                                               @RequestBody Map<String, Object> event) {
        return publish(customerId, "customer:" + customerId, event);
    }

    @PostMapping("/token")
    public Map<String, Object> token(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> request = body == null ? Collections.<String, String>emptyMap() : body;
        String agentId = request.get("agentId");
        String customerId = request.get("customerId");
        String subject;
        String role;
        String channel;
        boolean hasAgent = StringUtils.hasText(agentId);
        boolean hasCustomer = StringUtils.hasText(customerId);
        if (hasAgent && hasCustomer) {
            // 契约是"agentId 或 customerId"二选一；静默偏向 agent 会掩盖调用方的入参错误。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId与customerId只能提供一个");
        }
        if (hasAgent) {
            subject = agentId;
            role = "agent";
            channel = "agent:" + agentId;
        } else if (hasCustomer) {
            subject = customerId;
            role = "customer";
            channel = "customer:" + customerId;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId或customerId至少传一个");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("token", tokenService.issue(subject, role));
        result.put("url", properties.getWsUrl());
        result.put("channel", channel);
        result.put("subject", subject);
        result.put("role", role);
        return result;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("service", "message-svc");
        result.put("websocketEndpoint", properties.getWebsocketEndpoint());
        result.put("connectedSubjects", registry.connectedSubjects());
        return result;
    }

    private Map<String, Object> publish(String subject, String channel, Map<String, Object> event) {
        if (event == null) {
            // 缺事件体（含字面量 null）按 400 拒绝，不再用空 Map 假装投递成功。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少事件体");
        }
        messaging.convertAndSendToUser(subject, "/queue/events", event);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("channel", channel);
        result.put("subject", subject);
        // delivered 只反映真实的个人队列订阅：连接在线但未订阅时 SimpleBroker 会静默
        // 丢弃消息，按连接态回答 true 就是"消息丢失当成功"。
        result.put("delivered", registry.isSubscribed(subject));
        return result;
    }
}
