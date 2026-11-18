package com.example.bank.business.controller;
import com.example.bank.business.evidence.TransactionDocumentService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/internal/documents")
public class TransactionDocumentController {
    private final TransactionDocumentService documents;
    public TransactionDocumentController(TransactionDocumentService documents){this.documents=documents;}
    @PostMapping("/signature") public Map<String,Object> signature(@RequestBody Map<String,Object> input){return wrap(documents.archiveSignature(input));}
    @PostMapping("/receipt") public Map<String,Object> receipt(@RequestBody Map<String,Object> input){return wrap(documents.receipt(input));}
    private Map<String,Object> wrap(Map<String,Object> data){Map<String,Object> result=new LinkedHashMap<>();result.put("code","000000");result.put("data",data);return result;}
}
