package com.example.bank.business.controller;

import com.example.bank.business.service.PasswordResetService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
public class PasswordResetController {
    private final PasswordResetService service;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.service.SmsVerificationService sms;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.evidence.PasswordDocumentService documents;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.application.AuthorizationService authorizations;
    public PasswordResetController(PasswordResetService service){this.service=service;}
    @PostMapping("/api/mock/password/cards") public Map<String,Object> cards(@RequestBody Map<String,Object> input){return response(service.cards(input));}
    @PostMapping("/api/mock/password/media-ocr") public Map<String,Object> media(@RequestBody Map<String,Object> input){return response(service.mediaOcr(input));}
    @PostMapping("/api/mock/password/identity") public Map<String,Object> identity(@RequestBody Map<String,Object> input){return response(service.identity(input));}
    @PostMapping("/api/mock/password/reset") public Map<String,Object> reset(@RequestBody Map<String,Object> input){return response(service.reset(input));}
    @PostMapping("/api/mock/password/review") public Map<String,Object> review(@RequestBody Map<String,Object> input){return response(service.review(input));}
    @PostMapping("/api/mock/sms/send") public Map<String,Object> smsSend(@RequestBody Map<String,Object> input){return response(sms.send(input));}
    @PostMapping("/api/mock/sms/verify") public Map<String,Object> smsVerify(@RequestBody Map<String,Object> input){return response(sms.verify(input));}
    @PostMapping("/api/mock/password/form") public Map<String,Object> form(@RequestBody Map<String,Object> input){sms.requireVerified(input);return response(documents.form(input));}
    @PostMapping("/api/mock/password/signature") public Map<String,Object> signature(@RequestBody Map<String,Object> input){return response(documents.signature(input));}
    @PostMapping("/api/mock/password/receipt") public Map<String,Object> receipt(@RequestBody Map<String,Object> input){return response(documents.receipt(input));}
    @PostMapping("/api/mock/authorization/create") public Map<String,Object> create(@RequestBody Map<String,Object> input){return response(authorizations.create(input));}
    @PostMapping("/api/mock/authorization/load") public Map<String,Object> load(@RequestBody Map<String,Object> input){return response(authorizations.load(input));}
    @PostMapping("/api/mock/authorization/decide") public Map<String,Object> decide(@RequestBody Map<String,Object> input){return response(authorizations.decide(input));}
    private Map<String,Object> response(Map<String,Object> data){Map<String,Object> result=new LinkedHashMap<>();result.put("code","000000");result.put("data",data);return result;}
}
