package com.example.bank.business.controller;
import com.example.bank.business.evidence.NodeEvidenceService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
@RestController
@RequestMapping("/api/evidence")
public class NodeEvidenceController {
    private final NodeEvidenceService service;
    public NodeEvidenceController(NodeEvidenceService service){this.service=service;}
    @PostMapping("/photo") public Map<String,Object> photo(@RequestParam MultipartFile file,@RequestParam String processId,@RequestParam String actorId,@RequestParam String actorRole,@RequestParam String fieldName,@RequestParam String eventId)throws Exception{return service.upload(file,processId,actorId,actorRole,fieldName,eventId);}
}
