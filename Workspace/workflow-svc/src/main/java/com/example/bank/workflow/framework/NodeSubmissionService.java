package com.example.bank.workflow.framework;

import com.example.bank.workflow.session.BusinessSessionService;
import org.flowable.engine.*;
import org.flowable.engine.runtime.*;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.bpmn.model.*;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.annotation.PostConstruct;
import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** 公共提交：精确定位、权限校验、JSON 合并、幂等记录和引擎推进共用同一本地事务。 */
@Service
public class NodeSubmissionService {
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final HistoryService history;
    private final RepositoryService repository;
    private final JdbcTemplate jdbc;
    private final FlowData data;
    private final BpmnMetaReader bpmnMeta;
    private final BusinessSessionService sessions;
    public static final String AUTO_ACTOR = "SYSTEM";
    public NodeSubmissionService(RuntimeService runtime, TaskService tasks, HistoryService history,
            RepositoryService repository, JdbcTemplate jdbc, FlowData data, BpmnMetaReader bpmnMeta,
            BusinessSessionService sessions) {
        this.runtime=runtime; this.tasks=tasks; this.history=history; this.repository=repository;
        this.jdbc=jdbc; this.data=data; this.bpmnMeta=bpmnMeta; this.sessions=sessions;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS node_submission (process_id VARCHAR(64) NOT NULL, request_id VARCHAR(80) NOT NULL, "
                + "node_id VARCHAR(100) NOT NULL, node_name VARCHAR(200), action_code VARCHAR(40), actor_id VARCHAR(100), "
                + "request_hash VARCHAR(64), submitted_values MEDIUMTEXT, submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(process_id, request_id))");
        column("visit_seq", "INT NOT NULL DEFAULT 1");
        column("task_id", "VARCHAR(64)"); column("act_inst_id", "VARCHAR(64)");
        column("schema_version", "VARCHAR(20)"); column("execution_id", "VARCHAR(64)");
        column("entry_seq", "BIGINT NOT NULL AUTO_INCREMENT UNIQUE");
        jdbc.execute("ALTER TABLE node_submission MODIFY submitted_values MEDIUMTEXT");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_submission_lock (process_id VARCHAR(64) PRIMARY KEY)");
    }

    private void column(String name, String type) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='node_submission' AND column_name=?", Integer.class, name);
        if (n == null || n == 0) jdbc.execute("ALTER TABLE node_submission ADD COLUMN " + name + " " + type);
    }

    @Transactional
    public boolean submit(String processId, NodeSubmission request) {
        if (request.requestId == null || !request.requestId.matches("[a-zA-Z0-9_-]{8,80}")) bad("缺少或非法的 requestId");
        if (blank(request.nodeId) || blank(request.action)) bad("缺少 nodeId 或 action，请先读取当前节点");
        String actor = !blank(request.agentId) ? request.agentId : request.clientId;
        if (blank(actor) || actor.length() > 100 || "SYSTEM".equals(actor)) bad("缺少有效的参与方标识");
        if (!blank(request.agentId) && !blank(request.clientId)) bad("不能同时使用坐席与客户身份");
        Map<String, Object> supplied;
        try { supplied = request.resolveValues(data); } catch (IllegalArgumentException e) { bad(e.getMessage()); return false; }
        // 结果变量只能由能力节点产生，客户端不能注入风控/核验结论来改变网关分支。
        for (String protectedKey : Arrays.asList("retain_agent", "agent_work_complete", "signatureDocumentId", "receiptPdfUrl", "authPassed", "needHuman", "riskScore", "riskDecision", "coreTxId")) {
            if (supplied.containsKey(protectedKey)) bad("禁止提交系统计算字段: " + protectedKey);
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("nodeId", request.nodeId); canonical.put("taskId", request.taskId);
        canonical.put("executionId", request.executionId); canonical.put("action", request.action);
        canonical.put("actor", actor); canonical.put("role", blank(request.agentId) ? "CUSTOMER" : "AGENT");
        canonical.put("expectedRevision", request.expectedRevision); canonical.put("payload", supplied);
        String hash = digest(data.encode(data.canonical(canonical)));

        // 数据库锁覆盖重复请求、JSON 合并和访问编号分配；跨 JVM 同样有效。
        jdbc.update("INSERT IGNORE INTO workflow_submission_lock(process_id) VALUES(?)", processId);
        jdbc.queryForList("SELECT process_id FROM workflow_submission_lock WHERE process_id=? FOR UPDATE", processId);
        List<Map<String,Object>> existing = jdbc.queryForList("SELECT request_hash,actor_id FROM node_submission WHERE process_id=? AND request_id=?", processId, request.requestId);
        if (!existing.isEmpty()) {
            if (!actor.equals(existing.get(0).get("actor_id")) || !hash.equals(existing.get(0).get("request_hash"))) conflict("同一请求标识不能用于不同提交内容或身份");
            return true;
        }
        ProcessInstance instance = runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult();
        if (instance == null || instance.isSuspended()) conflict("流程已结束或暂停");
        // 改为从 BPMN 读取校验规则
        if (!bpmnMeta.isSubmittable(instance.getProcessDefinitionId(), request.nodeId)) {
            bad("当前节点不允许公共提交");
        }
        boolean agent = !blank(request.agentId);
        String actorRole = agent ? "AGENT" : "CUSTOMER";
        if (!bpmnMeta.isActorAllowed(instance.getProcessDefinitionId(), request.nodeId, actorRole)) {
            bad("当前参与方不能执行此动作");
        }
        if (!bpmnMeta.isActionAllowed(instance.getProcessDefinitionId(), request.nodeId, request.action)) {
            bad("当前节点不允许此动作");
        }
        Map<String,Object> context = runtime.getVariables(processId);
        if (agent) sessions.requireOwner(processId, actor);
        else if (blank(String.valueOf(context.getOrDefault("mobile_peer_id", ""))) || !Objects.equals(actor, context.get("mobile_peer_id"))) bad("客户会话与业务不匹配");
        long revision = ((Number) context.getOrDefault("submission_revision", 0L)).longValue();
        if (request.expectedRevision != null && request.expectedRevision != revision) conflict("表单已更新，请刷新后重试");

        Task task = null; Execution execution = null;
        FlowElement element = repository.getBpmnModel(instance.getProcessDefinitionId()).getFlowElement(request.nodeId);
        if (element instanceof UserTask) {
            if (blank(request.taskId)) bad("人工任务必须提供 taskId");
            task = tasks.createTaskQuery().processInstanceId(processId).taskId(request.taskId).singleResult();
            if (task == null || !request.nodeId.equals(task.getTaskDefinitionKey())) conflict("任务不存在或与节点不匹配");
            if (!blank(task.getAssignee()) && !actor.equals(task.getAssignee())) conflict("任务不属于当前参与方");
        } else if (element instanceof ReceiveTask) {
            if (!blank(request.taskId)) bad("等待节点不能携带 taskId");
            List<Execution> waiting = runtime.createExecutionQuery().processInstanceId(processId).activityId(request.nodeId).list();
            if (!blank(request.executionId)) waiting.removeIf(e -> !request.executionId.equals(e.getId()));
            if (waiting.size() != 1) conflict("等待实例不唯一或已失效，请刷新后精确提交");
            execution = waiting.get(0);
        } else { bad("只有人工任务或交互等待节点可以提交"); }

        Map<String,Object> policy=bpmnMeta.nodePolicy(instance.getProcessDefinitionId(),request.nodeId,data);
        java.util.Set<String> allowed=new java.util.HashSet<>();
        // 图片、签字等先通过公共留痕接口上传；这里仅携带该次上传的不可替代凭证。
        allowed.add("evidenceReceipts");
        if(supplied.containsKey("evidenceReceipts") && !(supplied.get("evidenceReceipts") instanceof Map))bad("影像凭证格式不正确");
        for(Map<String,Object> field:(List<Map<String,Object>>)policy.getOrDefault("fields",Collections.emptyList())){
            String name=String.valueOf(field.get("name"));allowed.add(name);Object value=supplied.containsKey(name)?supplied.get(name):data.project(context).get(name);
            if(!"REJECT".equals(request.action)&&Boolean.TRUE.equals(field.get("required"))&&(value==null||String.valueOf(value).trim().isEmpty()||("checkbox".equals(field.get("type"))&&!Boolean.TRUE.equals(supplied.get(name)))))bad("请填写或确认："+field.get("label"));
            if(value!=null&&field.get("pattern")!=null&&!String.valueOf(value).matches(String.valueOf(field.get("pattern"))))bad("格式不正确："+field.get("label"));
            if(value!=null&&"number".equals(field.get("type"))){try{java.math.BigDecimal number=new java.math.BigDecimal(String.valueOf(value));if(number.signum()<=0)bad("金额必须大于零");}catch(NumberFormatException e){bad("金额格式不正确");}}
        }
        for(String name:supplied.keySet())if(!allowed.contains(name))bad("当前节点不允许提交字段："+name);
        if("REJECT".equals(request.action)&&String.valueOf(supplied.getOrDefault("review_reason","")).trim().isEmpty())bad("请填写退回原因");
        Map<String,Object> values = validate(supplied, data.project(context));
        Map<String,Object> payload = data.merge(data.object(context.get("payload")), values);
        Map<String,Object> engine = new LinkedHashMap<>();
        engine.put("payload", data.encode(payload)); engine.put("action", request.action);
        engine.put("submission_revision", revision + 1);engine.put("submission_request_id",request.requestId);
        if (agent) engine.put("last_agent_id", actor);
        String executionId = task == null ? execution.getId() : task.getExecutionId();
        List<HistoricActivityInstance> visits = history.createHistoricActivityInstanceQuery().processInstanceId(processId)
                .activityId(request.nodeId).executionId(executionId).unfinished().list();
        String activityId = visits.size() == 1 ? visits.get(0).getId() : null;
        // 从 BPMN 读取节点名称
        // 从 BPMN 读取节点名称；节点已通过 isSubmittable 校验，元数据缺失按配置错误如实抛出
        Map<String, String> meta = bpmnMeta.nodeMeta(instance.getProcessDefinitionId(), request.nodeId);
        String nodeName = meta.get("nodeName");
        if (nodeName == null || nodeName.trim().isEmpty()) nodeName = request.nodeId;
        insert(processId, request.requestId, request.nodeId, nodeName, request.action, actor, hash,
                payload, task == null ? null : task.getId(), activityId, executionId, "1");
        if (task != null) tasks.complete(task.getId(), engine);
        else runtime.trigger(execution.getId(), engine);
        return false;
    }

    /** 能力输出单独留存；回放不会用当前变量冒充当时的结果。 */
    public void record(String processId, String nodeId, String executionId, String definitionId, Map<String,Object> output) {
        // 从 BPMN 读取节点名称；能力节点由 ApiTaskDelegate 以当前流程元素提交，
        // 元数据缺失（流程定义与执行不一致）按配置错误如实抛出，使本次能力执行整体回滚
        Map<String, String> meta = bpmnMeta.nodeMeta(definitionId, nodeId);
        String nodeName = meta.get("nodeName");
        if (nodeName == null || nodeName.trim().isEmpty()) nodeName = nodeId;
        insert(processId, "auto-" + UUID.randomUUID(), nodeId, nodeName,
                "AUTO", AUTO_ACTOR, digest(data.encode(output)), output, null, null, executionId, "1");
    }

    public int nextVisitSeq(String processId, String nodeId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(visit_seq),0) FROM node_submission WHERE process_id=? AND node_id=?", Integer.class, processId, nodeId);
        return (max == null ? 0 : max) + 1;
    }

    private void insert(String processId, String requestId, String nodeId, String name, String action, String actor,
                        String hash, Map<String,Object> values, String task, String activity, String execution, String version) {
        jdbc.update("INSERT INTO node_submission(process_id,request_id,node_id,node_name,action_code,actor_id,request_hash,submitted_values,visit_seq,task_id,act_inst_id,execution_id,schema_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                processId, requestId, nodeId, name, action, actor, hash, data.encode(values), nextVisitSeq(processId,nodeId), task, activity, execution, version);
    }

    /**
     * 提交值复制一份进入合并管线。字段的必填、格式、数值校验在 {@link #submit} 内完成
     * （见 fields 循环与 allowed 集合校验），失败即 400，本方法不做二次校验。
     *
     * @param supplied 前端提交的数据（已通过字段校验）
     * @param context 流程变量投影，仅保留签名以贴合既有调用形状
     * @return 供合并使用的副本
     */
    public Map<String,Object> validate(Map<String,Object> supplied, Map<String,Object> context) {
        return new LinkedHashMap<>(supplied);
    }

    public List<Map<String,Object>> visits(String processId) {
        return jdbc.queryForList("SELECT * FROM node_submission WHERE process_id=? ORDER BY entry_seq", processId);
    }
    public List<Map<String,Object>> history(String processId) { return visits(processId); }
    private static String digest(String text) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x",b)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
