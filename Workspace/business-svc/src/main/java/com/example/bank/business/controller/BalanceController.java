package com.example.bank.business.controller;

import com.example.bank.business.port.ProcessPort;
import com.example.bank.business.service.BizRecordService;
import com.example.bank.business.service.DigitalHumanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 业务服务业务支撑接口：数字人交互、双录存证、指标与历史查询。
 *
 * <p>本控制器专注于业务服务自身职责，不持有静态全局流程状态，流程推进统一走 Flowable 通用流程接口。</p>
 */
@RestController
@RequestMapping("/api/balance")
public class BalanceController {

    @Autowired private ProcessPort process;
    @Autowired private DigitalHumanService digitalHumanService;
    @Autowired private BizRecordService bizRecordService;
    @Autowired private com.example.bank.business.mock.MockCustomerContextService customerContext;
    @Autowired private com.example.bank.business.application.CallSessionService calls;

    @GetMapping("/ai/config")
    public Map<String, Object> aiConfig() {
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", digitalHumanService.getDigitalHumanConfig());
        return res;
    }

    @PostMapping("/ai/chat")
    @SuppressWarnings("unchecked")
    public Map<String,Object> aiChat(@RequestBody Map<String,Object> payload) {
        String activeId = String.valueOf(payload.getOrDefault("processId", ""));
        if (!activeId.isEmpty()) {
            Map<String,Object> call=calls.byProcess(activeId);
            if(call!=null && "OPEN".equals(call.get("status")))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"人工通话期间请通过文字聊天发送消息");
            Map<String,Object> activeVars = process.variables(activeId);
            if (activeVars.get("agent_session_id") != null && !String.valueOf(activeVars.get("agent_session_id")).isEmpty()
                    && !"released".equals(String.valueOf(activeVars.get("takeover_mode")))) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                        "人工会话期间请通过文字聊天发送消息");
            }
        }
        Map<String,Object> result=digitalHumanService.processChat(payload);
        String action=String.valueOf(result.get("action"));
        if(!"SUBMIT".equals(action) && !"TRIGGER".equals(action)) return result;
        String peer=String.valueOf(payload.getOrDefault("peerId",""));
        if(peer.trim().isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"缺少客户会话");
        String id=String.valueOf(result.getOrDefault("processId",""));
        if(id.isEmpty()) {
            Map<String,Object> vars=result.get("startVars") instanceof Map ? new LinkedHashMap<>((Map<String,Object>)result.get("startVars")) : new LinkedHashMap<>();
            vars.put("mobile_peer_id",peer);
            vars.put("customer_id",String.valueOf(payload.getOrDefault("customerId",peer)));
            vars.putIfAbsent("customer_name",vars.getOrDefault("customer","客户"));
            customerContext.enrich(vars);
            vars.put("biz_type",result.get("intent"));
            // 核验结论只能来自能力接口，清除旧意图解析器附加的结论。
            vars.remove("authPassed");
            String bizNo=String.valueOf(result.getOrDefault("bizNo","BIZ"+UUID.randomUUID()));
            vars.put("biz_no",bizNo);
            id=process.start(String.valueOf(result.get("processKey")),bizNo,vars);
            bizRecordService.createRecord(bizNo,id,String.valueOf(vars.get("customer_name")),"",String.valueOf(result.get("intent")),0D,"","DIGITAL_ONLY");
            bizRecordService.updateTradeInfo(bizNo,(String)vars.get("channel"),null,(String)vars.get("customer_id"),
                    (String)vars.get("trade_code"),(String)vars.get("trade_name"));
            result.put("processId",id);
        }
        Map<String,Object> state=process.state(id);
        Map<String,Object> policy=state.get("nodePolicy") instanceof Map ? (Map<String,Object>)state.get("nodePolicy") : Collections.emptyMap();
        Map<String,Object> proposed=result.get("slots") instanceof Map ? new LinkedHashMap<>((Map<String,Object>)result.get("slots")) : new LinkedHashMap<>();
        if(Boolean.TRUE.equals(proposed.remove("transfer_requested"))) {
            result.put("proposedAction","REQUEST_HUMAN"); proposed.clear();
        } else result.put("proposedAction",policy.get("action"));
        result.put("action","form");result.put("state",state);result.put("payload",proposed);
        return result;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(value = "processId", required = false) String processId) {
        if (processId != null && !processId.trim().isEmpty()) {
            return process.state(processId);
        }
        Map<String, Object> res = new HashMap<>();
        res.put("node", "none");
        return res;
    }

    @PostMapping("/record/upload-segment")
    public Map<String, Object> uploadSegment(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bizNo", required = false) String bizNo,
            @RequestParam(value = "processId", required = false) String processId,
            @RequestParam(value = "segmentIndex", defaultValue = "1") int segmentIndex,
            @RequestParam(value = "handlerType", defaultValue = "DIGITAL_HUMAN") String handlerType,
            @RequestParam(value = "handlerName", defaultValue = "智能数字员工·晨曦") String handlerName,
            @RequestParam(value = "duration", defaultValue = "0") int duration,
            @RequestParam(value = "nodesCovered", defaultValue = "") String nodesCovered
    ) {
        Map<String, Object> res = new HashMap<>();
        try {
            String bNo = (bizNo == null || bizNo.isEmpty()) ? "RECORD" + System.currentTimeMillis() : bizNo;
            File recordsDir = new File("records");
            if (!recordsDir.exists()) recordsDir.mkdirs();

            String fileName = bNo + "_seg" + segmentIndex + "_" + handlerType + "_" + System.currentTimeMillis() + ".webm";
            File targetFile = new File(recordsDir, fileName);
            file.transferTo(targetFile.getAbsoluteFile());

            // 计算 SHA-256 司法防篡改哈希
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new FileInputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            byte[] digest = md.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                hexString.append(String.format("%02x", b));
            }
            String fileHash = hexString.toString();
            String fileUrl = "/records/" + fileName;

            bizRecordService.addSegment(bNo, processId, segmentIndex, handlerType, handlerName, fileName, fileUrl, targetFile.length(), duration, fileHash, nodesCovered);

            // 存证记录同步写入 Flowable 流程变量。
            // 不吞异常：双录分段的哈希是司法防篡改的锚点，写不进去等于这一段录像在流程里没有出处，
            // 而后面的成功回执会让前端以为已经存证完毕。失败如实上抛，由调用方重传。
            if (processId != null && !processId.isEmpty()) {
                Map<String, Object> segVars = new LinkedHashMap<>();
                segVars.put("segment_" + segmentIndex + "_url", fileUrl);
                segVars.put("segment_" + segmentIndex + "_hash", fileHash);
                segVars.put("segment_" + segmentIndex + "_handler", handlerType);
                process.setVariables(processId, segVars);
            }

            res.put("success", true);
            res.put("fileUrl", fileUrl);
            res.put("fileHash", fileHash);
            res.put("fileName", fileName);
        } catch (Exception e) {
            res.put("success", false);
            res.put("error", e.getMessage());
        }
        return res;
    }

    @PostMapping("/action/log")
    public Map<String, Object> logAction(@RequestBody Map<String, String> payload) {
        String bizNo = payload.get("bizNo");
        if (bizNo == null || bizNo.isEmpty()) bizNo = "ACT" + System.currentTimeMillis();
        String actionType = payload.get("actionType");
        String actionTitle = payload.get("actionTitle");
        String actionPayload = payload.get("payload");
        String clientTime = payload.get("clientTime");
        if (clientTime == null) clientTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        bizRecordService.logAction(bizNo, actionType, actionTitle, actionPayload, clientTime);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        return res;
    }

    @PostMapping("/record/stream-chunk")
    public Map<String, Object> streamChunk(
            @RequestParam("chunk") MultipartFile chunk,
            @RequestParam(value = "bizNo", required = false) String bizNo,
            @RequestParam(value = "processId", required = false) String processId,
            @RequestParam(value = "segmentIndex", defaultValue = "1") int segmentIndex,
            @RequestParam(value = "handlerType", defaultValue = "DIGITAL_HUMAN") String handlerType,
            @RequestParam(value = "handlerName", defaultValue = "智能数字员工·晨曦") String handlerName,
            @RequestParam(value = "duration", defaultValue = "0") int duration,
            @RequestParam(value = "nodesCovered", defaultValue = "") String nodesCovered,
            @RequestParam(value = "isLast", defaultValue = "false") boolean isLast
    ) {
        try {
            String bNo = (bizNo == null || bizNo.isEmpty()) ? "CHUNK" + System.currentTimeMillis() : bizNo;
            byte[] bytes = chunk.getBytes();
            return bizRecordService.appendStreamChunk(bNo, processId, segmentIndex, handlerType, handlerName, duration, nodesCovered, bytes, isLast);
        } catch (Exception e) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("error", e.getMessage());
            return res;
        }
    }

    @GetMapping("/history/list")
    public Map<String, Object> listHistory(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "handlerMode", required = false) String handlerMode,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "0") int pageSize
    ) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        if (pageSize > 0) {
            Map<String, Object> paged = bizRecordService.listRecords(keyword, handlerMode, page, pageSize);
            res.put("data", paged.get("rows"));
            res.put("total", paged.get("total"));
            res.put("page", paged.get("page"));
            res.put("pageSize", paged.get("pageSize"));
        } else {
            res.put("data", bizRecordService.listRecords(keyword, handlerMode, limit));
        }
        return res;
    }

    @GetMapping("/history/detail")
    public Map<String, Object> getHistoryDetail(@RequestParam("bizNo") String bizNo) {
        Map<String, Object> res = new HashMap<>();
        Map<String, Object> detail = bizRecordService.getRecordDetail(bizNo);
        if (detail != null) {
            res.put("success", true);
            res.put("data", detail);
        } else {
            res.put("success", false);
            res.put("error", "未查询到业务流水 " + bizNo);
        }
        return res;
    }

    @GetMapping("/metrics/today")
    public Map<String, Object> getTodayMetrics() {
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", bizRecordService.getTodayMetrics());
        return res;
    }
}
