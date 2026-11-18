package com.example.bank.business.controller;

import com.example.bank.business.evidence.InteractionTraceService;
import com.example.bank.business.service.BizRecordService;
import com.example.bank.business.application.ConversationRelayService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.example.bank.business.evidence.InteractionTraceService.text;

@RestController
@RequestMapping("/api/evidence")
public class InteractionTraceController {
    private final InteractionTraceService traces;
    private final BizRecordService records;
    private final ConversationRelayService conversations;
    public InteractionTraceController(InteractionTraceService traces, BizRecordService records, ConversationRelayService conversations) { this.traces = traces; this.records = records; this.conversations = conversations; }

    @GetMapping("/trace")
    public Map<String,Object> list(@RequestParam String bizNo, @RequestParam(defaultValue="0") long afterId,
                                   @RequestParam(defaultValue="100") int limit) { return traces.list(bizNo, afterId, limit); }

    @GetMapping("/phase-trace")
    public Map<String,Object> phase(@RequestParam String bizNo,@RequestParam int segmentIndex,@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="100") int limit) {
        return traces.listPhase(bizNo,segmentIndex,afterId,limit);
    }
    @PostMapping("/trace")
    public Map<String,Object> append(@RequestBody Map<String,Object> body) {
        String bizNo = traces.identifier(body.get("bizNo"), 64);
        String type = text(body.get("eventType"));
        if (type.startsWith("CHAT_") || type.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户端不能写入服务端对话回执");
        String role = text(body.get("actorRole"));
        if (!Arrays.asList("CUSTOMER", "AGENT").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的操作来源");
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("source", "CLIENT"); payload.put("detail", body.get("payload")); payload.put("actorId", body.get("actorId"));
        records.ensureConversation(bizNo);
        boolean saved = traces.append(bizNo, text(body.get("eventId")), text(body.get("processId")), text(body.get("nodeName")),
                role, type, payload, text(body.get("clientTime")));
        return new LinkedHashMap<String,Object>() {{ put("success", true); put("duplicate", !saved); }};
    }

    /** 坐席文字消息也是业务交互留痕，由业务服务落库后供双方会话界面读取。 */
    @PostMapping("/chat")
    public Map<String,Object> chat(@RequestBody Map<String,Object> body) {
        return conversations.send(body);
    }

}
