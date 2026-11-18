package com.example.bank.business.controller;
import com.example.bank.business.service.EvaluationService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {
    private final EvaluationService service;
    public EvaluationController(EvaluationService service){this.service=service;}
    @GetMapping("/tags") public List<Map<String,Object>> tags(@RequestParam(required=false) Integer stars){return service.tags(stars);}
    @PostMapping public Map<String,Object> submit(@RequestBody Map<String,Object> input){return service.submit(input);}
}
