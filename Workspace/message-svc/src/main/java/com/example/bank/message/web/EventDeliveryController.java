package com.example.bank.message.web;

import com.example.bank.message.service.MessageDeliveryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 领域通知的统一投递边界。流程决定目标与时机；信令仅负责实际通道传输。 */
@RestController
@RequestMapping("/internal/delivery")
public class EventDeliveryController {

    private final MessageDeliveryService delivery;

    public EventDeliveryController(MessageDeliveryService delivery) {
        this.delivery = delivery;
    }

    @PostMapping("/{audience}/{subject}")
    public Map<String, Object> deliver(@PathVariable String audience,
                                       @PathVariable String subject,
                                       @RequestBody Map<String, Object> event) {
        return delivery.deliver(audience, subject, event);
    }
}
