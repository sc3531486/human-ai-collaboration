package com.example.bank.workflow.sop;

import com.example.bank.workflow.port.ArchivePort;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.*;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** 按流程实例读取已提交状态，节点名称来自实例对应版本的 BPMN。 */
@Service
public class SopProcessService {
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.workflow.framework.FlowData data;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.workflow.framework.BpmnMetaReader bpmnMeta;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final RepositoryService repository;
    private final TaskService tasks;
    private final ArchivePort archive;

    public SopProcessService(RuntimeService runtime, HistoryService history, RepositoryService repository,
                             TaskService tasks, ArchivePort archive) {
        this.runtime = runtime;
        this.history = history;
        this.repository = repository;
        this.tasks = tasks;
        this.archive = archive;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(String processId) {
        if (processId == null || processId.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少流程实例标识");
        }
        HistoricProcessInstance historic = history.createHistoricProcessInstanceQuery()
                .processInstanceId(processId).singleResult();
        if (historic == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程实例不存在");
        ProcessInstance active = runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult();
        Map<String, Object> vars = new TreeMap<>();
        if (active != null) vars.putAll(runtime.getVariables(processId));
        else history.createHistoricVariableInstanceQuery().processInstanceId(processId).list()
                .forEach(v -> vars.put(v.getVariableName(), v.getValue()));
        vars.putAll(data.project(vars));
        List<String> activeIds = active == null ? Collections.emptyList() : runtime.getActiveActivityIds(processId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        activeIds.stream().distinct().sorted().forEach(id -> {
            FlowElement element = repository.getBpmnModel(historic.getProcessDefinitionId()).getFlowElement(id);
            if (element != null) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("nodeId", id);
                node.put("nodeName", element.getName());
                nodes.add(node);
            }
        });
        String status = active != null ? (active.isSuspended() ? "SUSPENDED" : "RUNNING")
                : historic.getDeleteReason() != null ? "CANCELLED" : "COMPLETED";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("processId", processId);
        result.put("processDefinitionId", historic.getProcessDefinitionId());
        result.put("processDefinitionKey", historic.getProcessDefinitionKey());
        result.put("processStatus", status);
        result.put("eventType", "COMPLETED".equals(status) ? "PROCESS_COMPLETED" : "NODE_CHANGED");
        result.put("nodeStatus", "RUNNING".equals(status) ? "ACTIVE" : status);
        result.put("activeNodes", nodes);
        // 多个活动节点时交由工作台提示选择，禁止任意选择第一条分支。
        result.put("nodeId", nodes.size() == 1 ? nodes.get(0).get("nodeId") : "COMPLETED".equals(status) ? "end" : "");
        result.put("nodeName", nodes.size() == 1 ? nodes.get(0).get("nodeName") : null);
        result.put("node", result.get("nodeId"));
        result.put("revision", vars.getOrDefault("submission_revision", 0L));
        result.put("payload", data.object(vars.get("payload")));
        List<Map<String,Object>> steps = new ArrayList<>();
        repository.getBpmnModel(historic.getProcessDefinitionId()).getMainProcess().getFlowElements().forEach(element -> {
            if (element instanceof org.flowable.bpmn.model.UserTask || element instanceof org.flowable.bpmn.model.ReceiveTask) {
                Map<String,Object> step = new LinkedHashMap<>();
                step.put("nodeId",element.getId()); step.put("nodeName",element.getName());
                step.putAll(bpmnMeta.stepPresentation(historic.getProcessDefinitionId(), element.getId()));
                steps.add(step);
            }
        });
        result.put("steps", steps);
        String policyNode=nodes.size()==1?String.valueOf(nodes.get(0).get("nodeId")):String.valueOf(vars.getOrDefault("result_node_id",""));
        if(!policyNode.isEmpty())result.put("nodePolicy",bpmnMeta.nodePolicy(historic.getProcessDefinitionId(),policyNode,data));
        result.put("notificationTargets",bpmnMeta.notificationTargets(historic.getProcessDefinitionId(),activeIds,active==null));
        result.put("sceneName",historic.getProcessDefinitionName());
        List<org.flowable.engine.runtime.Execution> executions = active == null ? Collections.emptyList() : runtime.createExecutionQuery()
                .processInstanceId(processId).list();
        List<Map<String,Object>> waiting = new ArrayList<>();
        for (org.flowable.engine.runtime.Execution execution : executions) {
            if (!activeIds.contains(execution.getActivityId())) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("nodeId", execution.getActivityId()); row.put("executionId", execution.getId());
            waiting.add(row);
        }
        result.put("activeTasks", waiting);
        result.put("executionId", waiting.size() == 1 ? waiting.get(0).get("executionId") : null);
        result.put("vars", vars);
        result.put("bizNo", vars.getOrDefault("biz_no", ""));
        result.put("mobilePeerId", vars.getOrDefault("mobile_peer_id", ""));
        result.put("agentPeerId", vars.getOrDefault("agent_peer_id", ""));
        result.put("takeover_mode", vars.getOrDefault("takeover_mode", "ai"));
        List<Task> pending = tasks.createTaskQuery().processInstanceId(processId).list();
        result.put("taskId", pending.size() == 1 ? pending.get(0).getId() : null);
        // 任务名与办理人供坐席端判断"当前是不是人工节点、该不该由我处理"；
        // 多任务时不给值，与 taskId 的取舍一致 —— 任意取第一条会把分支选择权凭空给出去
        result.put("taskName", pending.size() == 1 ? pending.get(0).getName() : null);
        result.put("taskAssignee", pending.size() == 1 ? pending.get(0).getAssignee() : null);
        List<Map<String, Object>> trail = new ArrayList<>();
        history.createHistoricActivityInstanceQuery().processInstanceId(processId)
                .orderByHistoricActivityInstanceStartTime().asc().list().forEach(a -> {
            if (a.getActivityName() != null && !a.getActivityType().contains("Gateway")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nodeId", a.getActivityId());
                row.put("nodeName", a.getActivityName());
                row.put("completed", a.getEndTime() != null);
                trail.add(row);
            }
        });
        result.put("history", trail);
        result.put("archiveStatus", archiveStatus(status, vars));
        return result;
    }

    /**
     * 存证归档状态：未完成的流程在本地短路，不走 HTTP。
     *
     * <p>把短路交给 business-svc 会让每一次快照请求都白发一次跨服务调用，
     * 而绝大多数流程尚未完成。调用时机是引擎事件（节点推进、流程终结）与归档等待队列，
     * 前者绝大多数落在这个短路分支上。"哪些双录分段是必需的"这条规则连同分段数据留在
     * business-svc，这里只判流程是否已结束。</p>
     *
     * <p>下游不可达时抛 {@code ArchiveUnavailableException}，由等待队列保留跟踪重试 ——
     * 见 {@code NodeEventService#refreshArchive}。</p>
     */
    private String archiveStatus(String processStatus, Map<String, Object> vars) {
        if (!"COMPLETED".equals(processStatus)) return "COLLECTING";
        return archive.archiveStatus(text(vars.get("biz_no")), text(vars.get("entry_type")),
                Boolean.TRUE.equals(vars.get("needHuman")));
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
