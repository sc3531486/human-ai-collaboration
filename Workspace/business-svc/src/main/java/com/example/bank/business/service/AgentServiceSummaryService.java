package com.example.bank.business.service;

import com.example.bank.business.application.CallSessionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import java.util.*;

/** 坐席服务小结：一通服务一份小结，明细表可登记本次服务包含的多笔业务。 */
@Service
public class AgentServiceSummaryService {
    private final JdbcTemplate jdbc;
    private final CallSessionService calls;
    private final ObjectMapper json;
    private final ServiceBusinessCatalog catalog;

    public AgentServiceSummaryService(JdbcTemplate jdbc, CallSessionService calls, ObjectMapper json, ServiceBusinessCatalog catalog) {
        this.jdbc = jdbc;
        this.calls = calls;
        this.json = json;
        this.catalog = catalog;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_service_summary(summary_id VARCHAR(64) PRIMARY KEY,call_id VARCHAR(64) NOT NULL,agent_id VARCHAR(100) NOT NULL,customer_id VARCHAR(100) NOT NULL,customer_name VARCHAR(100),service_labels_json MEDIUMTEXT NOT NULL,remark_text VARCHAR(1000),submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,UNIQUE KEY uq_agent_service_summary(call_id,agent_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_service_summary_business(summary_id VARCHAR(64) NOT NULL,biz_no VARCHAR(64) NOT NULL,process_id VARCHAR(64),trade_code VARCHAR(64),trade_name VARCHAR(120) NOT NULL,handling_result VARCHAR(32) NOT NULL,PRIMARY KEY(summary_id,biz_no))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_service_summary_context(summary_id VARCHAR(64) PRIMARY KEY,snapshot_json MEDIUMTEXT NOT NULL)");
    }

    public Map<String, Object> draft(String callId, String agentId) {
        Map<String, Object> call = verifiedCall(callId, agentId);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM agent_service_summary WHERE call_id=? AND agent_id=?", callId, agentId);
        if (!existing.isEmpty()) return detail(existing.get(0));
        Map<String,Object> result = serviceContext(call);
        result.put("businesses", catalog.list(call));
        result.put("labels", Collections.emptyList());
        result.put("remark", "");
        return result;
    }

    private Map<String,Object> serviceContext(Map<String,Object> call) {
        Map<String, Object> context = context(call);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("callId", call.get("call_id"));
        result.put("agentId", call.get("agent_id"));
        result.put("customerId", text(call.get("customer_id")));
        result.put("customerName", first(context, "customer_name", "customerName"));
        result.put("customerMobileMasked", first(context, "customer_mobile_masked", "mobileMasked"));
        result.put("incomingChannel", first(context, "channel", "access_channel", "accessChannel"));
        result.put("serviceStartedAt", call.get("created_at"));
        result.put("serviceEndedAt", call.get("ended_at"));
        result.put("serviceMode", first(context, "service_mode", "serviceMode"));
        result.put("endedBy", call.get("end_initiator"));
        return result;
    }

    @Transactional
    public Map<String, Object> submit(Map<String, Object> input) {
        String callId = required(input, "callId");
        String agentId = required(input, "agentId");
        Map<String, Object> call = verifiedCall(callId, agentId);
        List<Map<String, Object>> businesses = catalog.list(call);
        if(businesses.isEmpty()) bad("本次服务尚无业务记录，请确认业务登记已完成");
        // 客户信息、业务名称及办理结果只取服务端事实，浏览器仅提交标签和备注。
        if(input.get("businesses") instanceof List) {
            Set<String> allowed = new HashSet<>(); for(Map<String,Object> item:businesses) allowed.add(text(item.get("bizNo")));
            for(Object raw:(List<?>)input.get("businesses")) if(!(raw instanceof Map) || !allowed.contains(text(((Map<?,?>)raw).get("bizNo")))) bad("不能将其他服务的业务登记到当前小结");
        }
        jdbc.queryForList("SELECT call_id FROM biz_call_session WHERE call_id=? FOR UPDATE",callId);
        List<String> labels = stringList(input.get("labels"), 12, 60, "服务标签");
        String remark = text(input.get("remark"));
        if (remark.length() > 1000) bad("小结备注不能超过1000字");
        String summaryId = existingId(callId, agentId);
        try {
            String labelsJson = json.writeValueAsString(labels);
            jdbc.update("INSERT INTO agent_service_summary(summary_id,call_id,agent_id,customer_id,customer_name,service_labels_json,remark_text) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE customer_id=VALUES(customer_id),customer_name=VALUES(customer_name),service_labels_json=VALUES(service_labels_json),remark_text=VALUES(remark_text),updated_at=CURRENT_TIMESTAMP", summaryId, callId, agentId, call.get("customer_id"), first(context(call), "customer_name", "customerName"), labelsJson, remark);
            jdbc.update("INSERT INTO agent_service_summary_context(summary_id,snapshot_json) VALUES(?,?) ON DUPLICATE KEY UPDATE snapshot_json=VALUES(snapshot_json)",summaryId,json.writeValueAsString(serviceContext(call)));
            jdbc.update("DELETE FROM agent_service_summary_business WHERE summary_id=?", summaryId);
            for (Map<String, Object> business : businesses) {
                jdbc.update("INSERT INTO agent_service_summary_business(summary_id,biz_no,process_id,trade_code,trade_name,handling_result) VALUES(?,?,?,?,?,?)", summaryId, business.get("bizNo"), business.get("processId"), business.get("tradeCode"), business.get("tradeName"), business.get("handlingResult"));
            }
        } catch (Exception error) {
            throw new IllegalStateException("服务小结保存失败", error);
        }
        return detail(jdbc.queryForList("SELECT * FROM agent_service_summary WHERE summary_id=?", summaryId).get(0));
    }

    private String existingId(String callId, String agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT summary_id FROM agent_service_summary WHERE call_id=? AND agent_id=?", callId, agentId);
        return rows.isEmpty() ? "SUM-" + UUID.randomUUID() : text(rows.get(0).get("summary_id"));
    }

    private Map<String, Object> detail(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String,Object>> snapshots=jdbc.queryForList("SELECT snapshot_json FROM agent_service_summary_context WHERE summary_id=?",row.get("summary_id"));
        try { if(!snapshots.isEmpty()) result.putAll(json.readValue(text(snapshots.get(0).get("snapshot_json")),Map.class)); }
        catch(Exception error) { throw new IllegalStateException("小结服务信息读取失败",error); }
        result.put("summaryId", row.get("summary_id")); result.put("callId", row.get("call_id")); result.put("agentId", row.get("agent_id"));
        result.put("customerId", row.get("customer_id")); result.put("customerName", row.get("customer_name")); result.put("remark", text(row.get("remark_text")));
        result.put("submittedAt", row.get("submitted_at"));
        try { result.put("labels", json.readValue(text(row.get("service_labels_json")), new TypeReference<List<String>>() {})); }
        catch (Exception error) { throw new IllegalStateException("服务小结标签数据损坏", error); }
        result.put("businesses", jdbc.queryForList("SELECT biz_no AS bizNo,process_id AS processId,trade_code AS tradeCode,trade_name AS tradeName,handling_result AS handlingResult FROM agent_service_summary_business WHERE summary_id=? ORDER BY biz_no", row.get("summary_id")));
        return result;
    }

    private Map<String, Object> verifiedCall(String callId, String agentId) {
        Map<String, Object> call = calls.requiredCall(callId);
        if (!agentId.equals(text(call.get("agent_id")))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前坐席不能填写该服务小结");
        if (!"ENDED".equals(text(call.get("status")))) bad("视频通话结束后才能提交服务小结");
        return call;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> context(Map<String, Object> call) {
        try { return json.readValue(text(call.get("context_json")), Map.class); }
        catch (Exception error) { throw new IllegalStateException("通话业务上下文读取失败", error); }
    }
    private List<String> stringList(Object raw, int maxItems, int maxLength, String label) { List<?> values = raw instanceof List ? (List<?>) raw : Collections.emptyList(); if (values.size() > maxItems) bad(label + "数量过多"); List<String> result = new ArrayList<>(); for (Object value : values) { String item = text(value).trim(); if (item.isEmpty() || item.length() > maxLength) bad(label + "格式不正确"); if (!result.contains(item)) result.add(item); } return result; }
    private String first(Map<String, Object> source, String... keys) { for (String key : keys) { String value = text(source.get(key)); if (!value.isEmpty()) return value; } return ""; }
    private String required(Map<String, Object> source, String key) { String value = text(source.get(key)).trim(); if (value.isEmpty()) bad("缺少字段：" + key); return value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
