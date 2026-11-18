package com.example.bank.workflow.session;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** 单应用原型的业务认领边界，归属写入流程变量，替代全局当前业务指针。 */
@Service
public class BusinessSessionService {
    private final RuntimeService runtime;
    private final TaskService tasks;
    public BusinessSessionService(RuntimeService runtime, TaskService tasks) { this.runtime = runtime; this.tasks = tasks; }

    /**
     * 该坐席可见的人工待办。
     *
     * <p>不再按 {@code taskDefinitionKey("human_auth")} 过滤：能出现在待接列表里的就是人工待办，
     * 而"人工节点叫什么"是流程定义的事，写在这里会在 BPMN 改名时静默失效
     * —— 列表变空，且没有任何报错。与 {@code ProcessControlService#tasks} 的取法保持一致。</p>
     */
    public synchronized List<Map<String, Object>> queue(String agentId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Task task : tasks.createTaskQuery().active().orderByTaskCreateTime().asc().list()) {
            Map<String, Object> vars = runtime.getVariables(task.getProcessInstanceId());
            String owner = String.valueOf(vars.getOrDefault("agent_session_id", ""));
            if (!owner.isEmpty() && !owner.equals(agentId)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("processId", task.getProcessInstanceId());
            row.put("taskId", task.getId());
            row.put("nodeId", task.getTaskDefinitionKey());
            row.put("nodeName", task.getName());
            row.put("bizNo", vars.get("biz_no"));
            row.put("vars", vars);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 坐席认领人工会话。
     *
     * <p>一个流程实例同时只有一个待办任务，因此按实例查即可，无需再点名节点。</p>
     */
    public synchronized void claim(String processId, String agentId, String peerId) {
        if (agentId == null || agentId.trim().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少坐席会话标识");
        Task task = tasks.createTaskQuery().processInstanceId(processId).singleResult();
        if (task == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "业务已离开人工节点，请刷新队列");
        Map<String, Object> vars = runtime.getVariables(processId);
        String owner = String.valueOf(vars.getOrDefault("agent_session_id", ""));
        if (!owner.isEmpty() && !owner.equals(agentId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "业务已被其他坐席接听");
        Map<String, Object> values = new HashMap<>();
        values.put("agent_session_id", agentId);
        values.put("agent_peer_id", peerId == null ? "" : peerId);
        values.put("takeover_mode", "av");
        runtime.setVariables(processId, values);
    }

    /** 释放人工会话归属：交还数字人时清除坐席绑定，使业务重新由数字人承载。 */
    public synchronized void releaseClaim(String processId, String agentId) {
        requireOwner(processId,agentId);
        release(processId);
    }

    public synchronized void release(String processId) {
        Map<String, Object> values = new HashMap<>();
        values.put("agent_session_id", "");
        values.put("agent_peer_id", "");
        values.put("takeover_mode", "released");
        runtime.setVariables(processId, values);
    }

    /**
     * 坐席结束业务：终止流程实例，让业务真正离开人工节点。
     *
     * <p>AdvancedSOP 里 human_auth 只有「交还数字人」一条出边，没有作废分支：业务一旦停在人工节点，
     * 除非坐席把整条链路走完就永远留在那里，并持续出现在待接任务列表里。
     * 因此「关闭业务」必须终结实例，只清前端会话等于把业务丢在后台。</p>
     *
     * <p>幂等：流程已结束时返回 closed=false，前端关闭已完成的业务标签不会因此报错。</p>
     */
    public synchronized Map<String, Object> close(String processId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        ProcessInstance instance = runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult();
        if (instance == null) {
            result.put("closed", false);
            result.put("reason", "NOT_RUNNING");
            return result;
        }
        runtime.deleteProcessInstance(processId, "坐席结束业务");
        result.put("closed", true);
        return result;
    }

    public void requireOwner(String processId, String agentId) {
        Object owner = runtime.getVariable(processId, "agent_session_id");
        if (owner == null || !owner.equals(agentId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "请先接听并绑定当前业务");
    }
}
