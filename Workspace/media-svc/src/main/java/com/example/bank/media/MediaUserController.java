package com.example.bank.media;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/internal/media")
public class MediaUserController {
    private final MediaUserService users;
    public MediaUserController(MediaUserService users) { this.users = users; }
    @PostMapping("/users")
    public Map<String, Object> apply(@RequestBody Map<String, String> input) { return users.apply(input); }
}
