package com.example.bank.business.controller;

import com.example.bank.business.port.MessagePort;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 领域服务事件回调入口。
 *
 * <p>workflow 只回调业务事件，不直接知道 message-svc 的地址、频道和 STOMP 实现。
 * 这里是唯一的事件到实时消息的转换边界；消息投递失败会抛 5xx，让调用方 outbox 重试。</p>
 */
@RestController
@RequestMapping("/internal/notifications")
public class InternalNotificationController {

    private final MessagePort messages;

    public InternalNotificationController(MessagePort messages) {
        this.messages = messages;
    }

    @PostMapping("/agent/{agentId}")
    public Map<String, Object> agent(@PathVariable String agentId,
                                     @RequestBody(required = false) Map<String, Object> event) {
        messages.publishAgent(agentId, event == null ? Collections.<String, Object>emptyMap() : event);
        return delivered();
    }

    @PostMapping("/customer/{customerId}")
    public Map<String, Object> customer(@PathVariable String customerId,
                                        @RequestBody(required = false) Map<String, Object> event) {
        messages.publishCustomer(customerId, event == null ? Collections.<String, Object>emptyMap() : event);
        return delivered();
    }

    private Map<String, Object> delivered() {
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("delivered", true);
        return receipt;
    }
}
