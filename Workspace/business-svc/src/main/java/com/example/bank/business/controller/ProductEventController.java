package com.example.bank.business.controller;

import com.example.bank.business.application.ProductEventService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 产品领域事件统一入口。产品只上报通用事实，business 是唯一翻译层。
 *
 * <p>信封：{@code eventId / type / occurredAt / bizKey / payload}。按 {@code eventId} 幂等。</p>
 */
@RestController
@RequestMapping("/internal/events")
public class ProductEventController {

    private final ProductEventService events;

    public ProductEventController(ProductEventService events) {
        this.events = events;
    }

    @PostMapping("/{product}")
    public Map<String, Object> receive(@PathVariable String product, @RequestBody Map<String, Object> event) {
        return events.handle(product, event);
    }
}
