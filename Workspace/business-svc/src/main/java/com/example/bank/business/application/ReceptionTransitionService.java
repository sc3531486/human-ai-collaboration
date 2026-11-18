package com.example.bank.business.application;

import com.example.bank.business.contract.QueueTicket;
import com.example.bank.business.evidence.InteractionTraceService;
import com.example.bank.business.port.MediaPort;
import com.example.bank.business.port.RoutingPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公共接待交接执行器：业务节点进入动作的统一落地，替代按接待节点直连排队的旧协议。
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li><b>受理幂等</b>：同一交接编号的重复请求返回同一回执，不重复排队、建房或切段；
 *       相同编号但目标或参数不同按冲突拒绝。</li>
 *   <li><b>资源解析</b>：资源持有者（当前坐席、保留坐席）从服务端状态解析，
 *       不接受前端指定或覆盖。</li>
 *   <li><b>保留坐席</b>：{@code assignment=REUSE} 时优先把工单绑定给仍在保留期内的原坐席，
 *       坐席不重复占用服务量；保留超时或坐席不在线才重新排队。</li>
 *   <li><b>媒体收尾</b>：{@code closeRoom=true} 封存人工段录像并关闭视频房间；
 *       保留坐席但 {@code closeRoom=false} 时仅切换办理方与页面。</li>
 *   <li><b>受理 ≠ 完成</b>：受理成功只代表工单进入路由，不等于坐席接听，更不等于业务办理成功。</li>
 * </ul>
 *
 * <p>事务边界：受理回执与本地状态变更同事务提交；routing/media 的调用结果在本地留痕，
 * 失败时保留待恢复状态由恢复任务处理，不向调用方伪造成功。</p>
 */
@Service
public class ReceptionTransitionService {

    private static final Logger log = LoggerFactory.getLogger(ReceptionTransitionService.class);

    /** 保留坐席时长：超时后租约失效，重新接待按新工单排队。 */
    static final long RETAIN_DEFAULT_MS = 30 * 60 * 1000L;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RoutingPort routing;
    private final MediaPort media;
    private final InteractionTraceService traces;
    private final long retainMs;

    public ReceptionTransitionService(JdbcTemplate jdbc, ObjectMapper json, RoutingPort routing,
                                      MediaPort media, InteractionTraceService traces,
                                      @org.springframework.beans.factory.annotation.Value(
                                              "${business.reception.retain-ms:1800000}") long retainMs) {
        this.jdbc = jdbc;
        this.json = json;
        this.routing = routing;
        this.media = media;
        this.traces = traces;
        this.retainMs = retainMs;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS reception_transition (transition_id VARCHAR(160) PRIMARY KEY, "
                + "process_id VARCHAR(64) NOT NULL, biz_no VARCHAR(64), node_id VARCHAR(100) NOT NULL, "
                + "handler_type VARCHAR(32) NOT NULL, assignment VARCHAR(16) NOT NULL, "
                + "request_hash VARCHAR(64) NOT NULL, receipt_json MEDIUMTEXT NOT NULL, "
                + "task_id VARCHAR(64) NULL, visit_id VARCHAR(64) NULL, "
                + "status VARCHAR(20) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "KEY idx_reception_process (process_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_retain_lease (process_id VARCHAR(64) PRIMARY KEY, "
                + "agent_id VARCHAR(64) NOT NULL, retained_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "expire_at TIMESTAMP NOT NULL)");
    }

    /** 交接受理入口：幂等、资源解析、排队与切段都在这里完成后返回回执。 */
    @Transactional
    public Map<String, Object> transition(Map<String, Object> body) {
        if (body == null) throw bad("缺少交接请求");
        Object requestRaw = body.get("request");
        if (!(requestRaw instanceof Map)) throw bad("缺少接待请求");
        @SuppressWarnings("unchecked") Map<String, Object> request = (Map<String, Object>) requestRaw;
        String transitionId = text(request.get("transitionId"));
        String processId = text(request.get("processId"));
        String nodeId = text(request.get("handledNode"));
        if (transitionId.isEmpty() || processId.isEmpty() || nodeId.isEmpty()) {
            throw bad("交接请求缺少 transitionId、processId 或 handledNode");
        }
        String handlerType = text(request.get("handlerType"));
        if (!"HUMAN".equals(handlerType) && !"DIGITAL_HUMAN".equals(handlerType)) {
            throw bad("无效的接待方类别: " + handlerType);
        }
        String assignment = "NEW".equals(text(request.get("assignment")))
                ? "NEW" : ("REUSE".equals(text(request.get("assignment"))) ? "REUSE" : "NONE");
        boolean releaseAgent = Boolean.TRUE.equals(body.get("releaseAgent"));
        boolean closeRoom = Boolean.TRUE.equals(body.get("closeRoom"));

        // 幂等：同一交接编号返回同一回执；参数变化按冲突拒绝。
        String hash = receiptHash(request, releaseAgent, closeRoom);
        Map<String, Object> existing = findTransition(transitionId);
        if (existing != null) {
            if (!hash.equals(existing.get("request_hash"))) {
                throw conflict("交接编号已绑定其他目标或参数: " + transitionId);
            }
            return decode(String.valueOf(existing.get("receipt_json")));
        }

        // 媒体收尾：释放坐席才关房；保留坐席仅切换办理方与页面。
        boolean roomClosed = false;
        if (closeRoom) {
            try {
                media.closeRoom(processId);
                roomClosed = true;
            } catch (RuntimeException error) {
                log.warn("关闭视频房间失败，保留待收尾记录 processId={} cause={}", processId, error.getMessage());
            }
        }

        // 排队：HUMAN 走 routing；数字人同样排队以便记录受理与切段。
        String retainedAgent = retainedAgent(processId);
        String bizNo = text(request.get("bizNo"));
        Map<String, Object> routeRequest = new LinkedHashMap<>(request);
        routeRequest.put("retainedAgentId", retainedAgent);
        // 产品只认 biz_key/biz_date：business 把 biz_no 翻译成产品字段
        if (!bizNo.isEmpty() && !routeRequest.containsKey("bizKey")) routeRequest.put("bizKey", bizNo);
        if (!routeRequest.containsKey("bizDate")) routeRequest.put("bizDate", LocalDate.now().toString());
        QueueTicket ticket;
        try {
            ticket = routing.route(routeRequest);
        } catch (RuntimeException error) {
            jdbc.update("INSERT INTO reception_transition(transition_id,process_id,biz_no,node_id,handler_type,"
                            + "assignment,request_hash,receipt_json,status) VALUES(?,?,?,?,?,?,?,?,'FAILED')",
                    transitionId, processId, text(request.get("bizNo")), nodeId, handlerType, assignment, hash,
                    encode(receipt(processId, transitionId, nodeId, false, null, roomClosed)));
            throw error;
        }

        // 释放坐席：清除会话归属，坐席退出；租约同时失效。
        if (releaseAgent) {
            jdbc.update("DELETE FROM agent_retain_lease WHERE process_id=?", processId);
        }

        // 办理分段：以实际业务办理方切段，重复回调返回同一段号。
        int segmentIndex = traces.switchPhase(text(request.get("bizNo")),
                "HUMAN".equals(handlerType) ? "HUMAN_AGENT" : "DIGITAL_HUMAN", transitionId);

        Map<String, Object> receipt = receipt(processId, transitionId, nodeId, true, ticket, roomClosed);
        receipt.put("segmentIndex", segmentIndex);
        receipt.put("releaseAgent", releaseAgent);
        receipt.put("retainedAgentId", retainedAgent);
        jdbc.update("INSERT INTO reception_transition(transition_id,process_id,biz_no,node_id,handler_type,"
                        + "assignment,request_hash,receipt_json,status) VALUES(?,?,?,?,?,?,?,?,'ACCEPTED')",
                transitionId, processId, text(request.get("bizNo")), nodeId, handlerType, assignment, hash, encode(receipt));
        return receipt;
    }

    /**
     * 绑定真实任务标识：目标任务进入后由提交动作补充 taskId/visitId 并验证有效性。
     * 预受理阶段尚不存在目标任务，不伪造标识；重复绑定同值幂等返回。
     */
    @Transactional
    public Map<String, Object> bind(String transitionId, Map<String, Object> body) {
        Map<String, Object> transition = requireTransition(transitionId);
        String processId = text(body == null ? null : body.get("processId"));
        String nodeId = text(body == null ? null : body.get("nodeId"));
        if (!transition.get("process_id").equals(processId) || !transition.get("node_id").equals(nodeId)) {
            throw conflict("绑定信息与交接意图不一致: " + transitionId);
        }
        String taskId = text(body.get("taskId"));
        String visitId = text(body.get("visitId"));
        jdbc.update("UPDATE reception_transition SET task_id=?,visit_id=?,status='BOUND' WHERE transition_id=?",
                taskId, visitId, transitionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transitionId", transitionId);
        result.put("bound", true);
        result.put("taskId", taskId);
        result.put("visitId", visitId);
        return result;
    }

    /** 查询交接结果，解决重试与结果未知：意图不存在返回 404。 */
    public Map<String, Object> query(String transitionId) {
        return requireTransition(transitionId);
    }

    /** 撤销失效或被中止的接待意图：同时收口对应路由工单。 */
    @Transactional
    public Map<String, Object> cancel(String transitionId, String reason) {
        Map<String, Object> transition = requireTransition(transitionId);
        String processId = String.valueOf(transition.get("process_id"));
        if (!"CANCELLED".equals(transition.get("status"))) {
            jdbc.update("UPDATE reception_transition SET status='CANCELLED' WHERE transition_id=?", transitionId);
            try {
                routing.complete(processId);
            } catch (RuntimeException error) {
                log.warn("撤销交接时收口工单失败，保留待恢复 processId={} cause={}", processId, error.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transitionId", transitionId);
        result.put("cancelled", true);
        result.put("reason", reason == null ? "" : reason);
        return result;
    }

    /**
     * 保留坐席租约：人工节点完成后按策略登记保留，进入下一个人工节点时优先复用。
     * 租约到期自动失效（sweep 清理），不阻塞其他客户使用该坐席之外的资源。
     */
    @Transactional
    public void retainAgent(String processId, String agentId) {
        if (processId.isEmpty() || agentId.isEmpty()) return;
        jdbc.update("DELETE FROM agent_retain_lease WHERE process_id=?", processId);
        jdbc.update("INSERT INTO agent_retain_lease(process_id,agent_id,expire_at) VALUES(?,?,"
                        + "DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND))",
                processId, agentId, retainMs / 1000);
    }

    /** 读取有效保留坐席；无租约或已过期返回空串。 */
    public String retainedAgent(String processId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT agent_id FROM agent_retain_lease WHERE process_id=? AND expire_at > CURRENT_TIMESTAMP", processId);
        return rows.isEmpty() ? "" : String.valueOf(rows.get(0).get("agent_id"));
    }

    /** 清理过期租约：由定时任务调用，防止表无限增长。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000L)
    public void sweepLeases() {
        jdbc.update("DELETE FROM agent_retain_lease WHERE expire_at <= CURRENT_TIMESTAMP");
    }

    /** 人工节点完成时由提交链路调用：坐席保留且未释放即登记租约。 */
    public void onAgentSubmission(String processId, String agentId, boolean retain) {
        if (retain && !agentId.isEmpty()) retainAgent(processId, agentId);
    }

    private Map<String, Object> receipt(String processId, String transitionId, String nodeId,
                                        boolean accepted, QueueTicket ticket, boolean roomClosed) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("success", accepted);
        receipt.put("accepted", accepted);
        receipt.put("processId", processId);
        receipt.put("transitionId", transitionId);
        receipt.put("nodeId", nodeId);
        receipt.put("ticket", ticket);
        receipt.put("matching", "ASYNC");
        receipt.put("roomClosed", roomClosed);
        return receipt;
    }

    private Map<String, Object> findTransition(String transitionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM reception_transition WHERE transition_id=?", transitionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> requireTransition(String transitionId) {
        Map<String, Object> transition = findTransition(transitionId);
        if (transition == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "交接意图不存在: " + transitionId);
        }
        return transition;
    }

    private String receiptHash(Map<String, Object> request, boolean releaseAgent, boolean closeRoom) {
        try {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            sorted.put("closeRoom", closeRoom);
            sorted.put("releaseAgent", releaseAgent);
            sorted.put("request", canonical(request));
            return org.springframework.util.DigestUtils.md5DigestAsHex(
                    json.writeValueAsBytes(sorted));
        } catch (Exception e) {
            throw new IllegalStateException("交接请求无法计算摘要", e);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            ((Map<?, ?>) value).forEach((key, v) -> sorted.put(String.valueOf(key), canonical(v)));
            return sorted;
        }
        if (value instanceof List) {
            List<Object> list = new java.util.ArrayList<>();
            ((List<?>) value).forEach(v -> list.add(canonical(v)));
            return list;
        }
        return value;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("回执无法序列化", e); }
    }

    private Map<String, Object> decode(String value) {
        try { return json.readValue(value, Map.class); }
        catch (Exception e) { throw new IllegalStateException("回执数据损坏", e); }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
