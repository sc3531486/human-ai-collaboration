package com.example.bank.business.capability;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 内部能力入口：业务能力只返回结果，不调用流程推进接口。 */
@RestController
@RequestMapping("/internal/capabilities")
public class CapabilityController {
    private final CapabilityService service;
    public CapabilityController(CapabilityService service) { this.service=service; }
    @PostMapping("/{code}")
    public Map<String,Object> invoke(@PathVariable String code, @RequestBody Map<String,Object> request) {
        return service.invoke(code, request);
    }
}
