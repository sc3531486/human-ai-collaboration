package com.example.bank.business.controller;

import com.example.bank.business.port.MessagePort;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/** 对外暴露 message-svc 的业务级令牌接口。 */
@RestController
@RequestMapping("/api/message")
public class MessageController {

    private final MessagePort messages;

    public MessageController(MessagePort messages) {
        this.messages = messages;
    }

    @PostMapping("/token")
    public Map<String, Object> token(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> input = body == null ? Collections.<String, String>emptyMap() : body;
        return messages.token(input.get("agentId"), input.get("customerId"));
    }
}
