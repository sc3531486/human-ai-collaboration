package com.example.bank.business.evidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import java.util.*;
import java.util.function.Supplier;

/** 业务流水在流程创建前即确定；交互事件追加保存，客户请求重试复用原回执。 */
@Service
public class InteractionTraceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public InteractionTraceService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_interaction_trace (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "biz_no VARCHAR(64) NOT NULL, event_id VARCHAR(120) NOT NULL, process_id VARCHAR(64), "
                + "node_name VARCHAR(200), actor_role VARCHAR(32) NOT NULL, event_type VARCHAR(64) NOT NULL, "
                + "event_payload LONGTEXT NOT NULL, client_time VARCHAR(100), created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "UNIQUE(biz_no,event_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_evidence_owner(biz_no VARCHAR(64) PRIMARY KEY, segment_index INT NOT NULL DEFAULT 1, handler_type VARCHAR(32) NOT NULL DEFAULT 'DIGITAL_HUMAN')");
        jdbc.execute("CREATE TABLE IF NOT EXISTS biz_evidence_phase(biz_no VARCHAR(64) NOT NULL, segment_index INT NOT NULL, handler_type VARCHAR(32) NOT NULL, transition_id VARCHAR(160) NOT NULL, start_trace_id BIGINT NOT NULL, started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, ended_at TIMESTAMP NULL, PRIMARY KEY(biz_no,segment_index), UNIQUE(biz_no,transition_id))");
    }

    public void ensurePhase(String bizNo) {
        identifier(bizNo,64);
        jdbc.update("INSERT IGNORE INTO biz_evidence_owner(biz_no) VALUES(?)",bizNo);
        jdbc.update("INSERT IGNORE INTO biz_evidence_phase(biz_no,segment_index,handler_type,transition_id,start_trace_id) VALUES(?,1,'DIGITAL_HUMAN','initial',0)",bizNo);
    }

    /** Flowable 接待交接是唯一切段来源；回调重试返回同一段号。 */
    @Transactional
    public int switchPhase(String bizNo,String handler,String transitionId) {
        ensurePhase(bizNo);
        if(!Arrays.asList("DIGITAL_HUMAN","HUMAN_AGENT").contains(handler))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"无效的办理主体");
        identifier(transitionId,160);
        Map<String,Object> owner=jdbc.queryForMap("SELECT * FROM biz_evidence_owner WHERE biz_no=? FOR UPDATE",bizNo);
        List<Map<String,Object>> replay=jdbc.queryForList("SELECT segment_index FROM biz_evidence_phase WHERE biz_no=? AND transition_id=?",bizNo,transitionId);
        if(!replay.isEmpty())return ((Number)replay.get(0).get("segment_index")).intValue();
        int index=((Number)owner.get("segment_index")).intValue();
        if(handler.equals(owner.get("handler_type")))return index;
        jdbc.update("UPDATE biz_evidence_phase SET ended_at=CURRENT_TIMESTAMP WHERE biz_no=? AND segment_index=?",bizNo,index);
        long cursor=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM biz_interaction_trace WHERE biz_no=?",Long.class,bizNo);
        jdbc.update("INSERT INTO biz_evidence_phase(biz_no,segment_index,handler_type,transition_id,start_trace_id) VALUES(?,?,?,?,?)",bizNo,index+1,handler,transitionId,cursor);
        jdbc.update("UPDATE biz_evidence_owner SET segment_index=?,handler_type=? WHERE biz_no=?",index+1,handler,bizNo);
        return index+1;
    }

    public void closePhases(String bizNo) { jdbc.update("UPDATE biz_evidence_phase SET ended_at=CURRENT_TIMESTAMP WHERE biz_no=? AND ended_at IS NULL",bizNo); }
    public void requirePhase(String bizNo,int index,String handler) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM biz_evidence_phase WHERE biz_no=? AND segment_index=? AND handler_type=?",Integer.class,bizNo,index,handler)!=1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"录像段号与流程办理主体不匹配");
    }
    public List<Map<String,Object>> phases(String bizNo) {
        List<Map<String,Object>> phases=jdbc.queryForList("SELECT segment_index,handler_type,started_at,ended_at,start_trace_id FROM biz_evidence_phase WHERE biz_no=? ORDER BY segment_index",bizNo);
        for(Map<String,Object> phase:phases)phase.put("operations",listPhase(bizNo,((Number)phase.get("segment_index")).intValue(),0,200));
        return phases;
    }
    public Map<String,Object> listPhase(String bizNo,int index,long after,int limit) {
        List<Map<String,Object>> phases=jdbc.queryForList("SELECT segment_index,start_trace_id FROM biz_evidence_phase WHERE biz_no=? AND segment_index>=? ORDER BY segment_index LIMIT 2",bizNo,index);
        if(phases.isEmpty()||((Number)phases.get(0).get("segment_index")).intValue()!=index)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"业务分段不存在");
        long start=((Number)phases.get(0).get("start_trace_id")).longValue();
        long end=phases.size()>1?((Number)phases.get(1).get("start_trace_id")).longValue():Long.MAX_VALUE;
        return listRange(bizNo,Math.max(start,after),end,limit);
    }
    public List<Map<String,Object>> images(String bizNo) {
        return jdbc.query("SELECT node_name,event_payload,created_time FROM biz_interaction_trace WHERE biz_no=? AND event_type IN ('CAPTURE_SAVED','DOCUMENT_ARCHIVED') ORDER BY id",(rs,n)-> {
            Map<String,Object> photo=decode(rs.getString("event_payload"));photo.put("nodeName",rs.getString("node_name"));photo.put("createdAt",rs.getTimestamp("created_time").getTime());return photo;
        },bizNo);
    }

    public Map<String,Object> chat(Map<String,Object> input, Supplier<Map<String,Object>> work) {
        String bizNo = identifier(input.get("bizNo"), 64);
        String requestId = identifier(input.get("requestId"), 100);
        Map<String,Object> request = new LinkedHashMap<>();
        for (String key : Arrays.asList("text", "peerId", "model", "processId")) request.put(key, input.get(key));
        boolean accepted = append(bizNo, requestId + ":request", text(input.get("processId")), "",
                "CUSTOMER", "CHAT_REQUEST", request, text(input.get("clientTime")));
        if (!accepted) {
            List<Map<String,Object>> receipt = jdbc.queryForList("SELECT event_payload FROM biz_interaction_trace WHERE biz_no=? AND event_id=?", bizNo, requestId + ":reply");
            if (receipt.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "这条对话仍在处理中，请稍后重试同一请求");
            return decode(String.valueOf(receipt.get(0).get("event_payload")));
        }
        try {
            Map<String,Object> response = new LinkedHashMap<>(work.get());
            response.put("bizNo", bizNo); response.put("requestId", requestId);
            Map<?,?> state = response.get("state") instanceof Map ? (Map<?,?>)response.get("state") : Collections.emptyMap();
            append(bizNo, requestId + ":reply", text(response.getOrDefault("processId", input.get("processId"))),
                    text(state.get("nodeName")), "DIGITAL_HUMAN", "CHAT_REPLY", response, "");
            return response;
        } catch (RuntimeException error) {
            Map<String,Object> failed = new LinkedHashMap<>();
            failed.put("success", false); failed.put("error", "对话处理未完成，请重新发起请求");
            failed.put("bizNo", bizNo); failed.put("requestId", requestId);
            append(bizNo, requestId + ":reply", text(input.get("processId")), "", "SYSTEM", "CHAT_FAILED", failed, "");
            throw error;
        }
    }

    public boolean append(String bizNo, String eventId, String processId, String nodeName, String role,
                          String type, Map<String,Object> payload, String clientTime) {
        identifier(bizNo, 64); identifier(eventId, 120);
        if (role == null || role.length() > 32 || type == null || type.length() > 64
                || processId.length() > 64 || nodeName.length() > 200 || clientTime.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "留痕字段长度不符合要求");
        ensurePhase(bizNo);
        String encoded = encode(payload);
        if (encoded.length() > 2000000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "交互记录过大");
        try {
            jdbc.update("INSERT INTO biz_interaction_trace(biz_no,event_id,process_id,node_name,actor_role,event_type,event_payload,client_time) VALUES(?,?,?,?,?,?,?,?)",
                    bizNo, eventId, processId, nodeName, role, type, encoded, clientTime);
            return true;
        } catch (DuplicateKeyException duplicate) {
            String previous = jdbc.queryForObject("SELECT event_payload FROM biz_interaction_trace WHERE biz_no=? AND event_id=?", String.class, bizNo, eventId);
            if (!encoded.equals(previous)) throw new ResponseStatusException(HttpStatus.CONFLICT, "同一留痕编号不能用于不同内容");
            return false;
        }
    }

    public Map<String,Object> list(String bizNo, long afterId, int limit) {
        return listRange(bizNo,afterId,Long.MAX_VALUE,limit);
    }
    private Map<String,Object> listRange(String bizNo,long afterId,long endId,int limit) {
        identifier(bizNo, 64);
        int size = Math.max(1, Math.min(limit, 200));
        List<Map<String,Object>> rows = jdbc.query("SELECT * FROM biz_interaction_trace WHERE biz_no=? AND id>? AND id<=? ORDER BY id ASC LIMIT ?", (rs, index) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id")); row.put("eventId", rs.getString("event_id"));
            row.put("processId", rs.getString("process_id")); row.put("nodeName", rs.getString("node_name"));
            row.put("actorRole", rs.getString("actor_role")); row.put("eventType", rs.getString("event_type"));
            row.put("payload", decode(rs.getString("event_payload"))); row.put("clientTime", rs.getString("client_time"));
            row.put("createdAt", rs.getTimestamp("created_time").getTime()); return row;
        }, bizNo, Math.max(0, afterId), endId, size + 1);
        boolean more = rows.size() > size;
        if (more) rows.remove(rows.size() - 1);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("success", true); result.put("rows", rows);
        result.put("hasMore", more); result.put("nextId", rows.isEmpty() ? afterId : rows.get(rows.size() - 1).get("id"));
        return result;
    }

    public String identifier(Object value, int max) {
        String id = text(value);
        if (id.isEmpty() || id.length() > max || !id.matches("[A-Za-z0-9_:.-]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少或无效的业务/请求编号");
        return id;
    }

    /** 新会话须有授权和每轮处理回执；旧业务没有该表记录时保留原有归档规则。 */
    public boolean conversationComplete(String bizNo) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT event_id,event_type,actor_role FROM biz_interaction_trace WHERE biz_no=?", bizNo);
        if (rows.isEmpty()) return true;
        Set<String> ids = new HashSet<>(); boolean consent = false, customer = false;
        for (Map<String,Object> row : rows) {
            ids.add(String.valueOf(row.get("event_id"))); consent |= "CONSENT_AGREED".equals(row.get("event_type"));
            customer |= "CUSTOMER".equals(row.get("actor_role"));
        }
        if (customer && !consent) return false;
        for (Map<String,Object> row : rows) if ("CHAT_REQUEST".equals(row.get("event_type"))) {
            String request = String.valueOf(row.get("event_id"));
            if (!ids.contains(request.substring(0, request.length() - ":request".length()) + ":reply")) return false;
        }
        return true;
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("留痕数据无法保存", e); } }
    private Map<String,Object> decode(String value) { try { return json.readValue(value, new TypeReference<LinkedHashMap<String,Object>>() {}); } catch (Exception e) { throw new IllegalStateException("留痕记录无法读取", e); } }
    public static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
