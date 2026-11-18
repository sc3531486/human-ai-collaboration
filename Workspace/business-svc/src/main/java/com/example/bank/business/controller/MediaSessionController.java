package com.example.bank.business.controller;

import com.example.bank.business.application.MediaSessionService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/media")
public class MediaSessionController {
    private final MediaSessionService sessions;
    public MediaSessionController(MediaSessionService sessions) { this.sessions = sessions; }
    @PostMapping("/users")
    public Map<String, Object> apply(@RequestBody Map<String, String> input) { return sessions.apply(input); }
}
