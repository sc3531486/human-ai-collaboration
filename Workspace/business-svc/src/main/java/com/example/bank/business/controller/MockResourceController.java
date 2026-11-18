package com.example.bank.business.controller;

import com.example.bank.business.mock.MockResourceCatalog;
import com.example.bank.business.service.AgentLoginService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class MockResourceController {
    private final MockResourceCatalog catalog;
    private final AgentLoginService accounts;
    public MockResourceController(MockResourceCatalog catalog, AgentLoginService accounts) { this.catalog = catalog; this.accounts = accounts; }
    @GetMapping("/internal/mock/resources")
    public Map<String, Object> resources() throws java.io.IOException {
        Map<String,Object> result = catalog.read(); result.put("agents",accounts.profiles()); return result;
    }
    @org.springframework.web.bind.annotation.GetMapping("/api/platform/agent-profile")
    public Map<String,Object> profile(@org.springframework.web.bind.annotation.RequestParam(required=false) String agentId) throws java.io.IOException {
        return accounts.profile(agentId);
    }
}
