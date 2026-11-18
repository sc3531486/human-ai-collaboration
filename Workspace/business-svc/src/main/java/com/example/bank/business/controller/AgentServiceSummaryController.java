package com.example.bank.business.controller;

import com.example.bank.business.service.AgentServiceSummaryService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/agent-service-summaries")
public class AgentServiceSummaryController {
    private final AgentServiceSummaryService summaries;
    public AgentServiceSummaryController(AgentServiceSummaryService summaries) { this.summaries = summaries; }
    @GetMapping("/draft") public Map<String, Object> draft(@RequestParam String callId, @RequestParam String agentId) { return summaries.draft(callId, agentId); }
    @PostMapping public Map<String, Object> submit(@RequestBody Map<String, Object> input) { return summaries.submit(input); }
}
