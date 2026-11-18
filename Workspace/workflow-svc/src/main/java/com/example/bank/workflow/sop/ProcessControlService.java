package com.example.bank.workflow.sop;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程控制：接收节点推进、待办任务、挂起激活、环境复位。
 *
 * <p>这些操作需要 Flowable 的引擎原语，而引擎只存在于本服务。调用方（业务逻辑服务）
 * 不持有流程数据库、也不认识 BPMN，因此这里提供的是<b>有业务含义的操作</b> ——
 * "在收款确认节点推进"、"完成坐席授权任务" —— 而不是引擎原语的逐个透出。
 * 逐个透出会把"先按节点查执行、再触发它"这类两步顺序泄漏到业务侧，
 * 让两个服务都懂流程引擎，拆分就白做了。</p>
 */
@Service
public class ProcessControlService {

    private final RuntimeService runtime;
    private final TaskService tasks;

    public ProcessControlService(RuntimeService runtime, TaskService tasks) {
        this.runtime = runtime;
        this.tasks = tasks;
    }

    /**
     * 在指定的接收节点上推进流程实例。
     *
     * <p>合并了原先散在各处的三步：按节点查执行 → 写变量 → 触发。
     * 调用方只需要说"我在 ai_collect 节点确认了这几项"，不需要知道执行 ID 是什么。</p>
     *
     * <p>节点上没有等待中的执行时返回 <b>409</b> 而不是静默成功：这通常意味着业务已经
     * 推进过（重复提交）或根本没走到该节点，两种情况调用方都要区分出来。</p>
     */
    @Transactional
    public Map<String, Object> trigger(String processId, String activityId, Map<String, Object> variables) {
        Execution execution = runtime.createExecutionQuery()
                .processInstanceId(processId).activityId(activityId).singleResult();
        if (execution == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前不在 " + activityId + " 节点，或已提交过");
        }
        Map<String, Object> values = variables == null ? Collections.<String, Object>emptyMap() : variables;
        if (!values.isEmpty()) runtime.setVariables(processId, values);
        // 触发时再带一次变量：接收节点可能因网关条件需要它们在本作用域内可见
        runtime.trigger(execution.getId(), values);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        result.put("activityId", activityId);
        result.put("triggered", true);
        return result;
    }

    /**
     * 完成待办任务。
     *
     * <p>任务不存在时返回 {@code completed:false} 而不是 4xx：调用方普遍把"已经没有待办了"
     * 当作幂等成功（重复提交、流程已推进），返回异常会让它们各自写一遍 try/catch
     * 去猜这个 4xx 到底是不是错误。真正需要报错的场景由调用方按 {@code reason} 判定。</p>
     *
     * @param taskDefinitionKey 可选，限定任务节点；为空则取该实例的任意待办
     * @param assignee          可选，办理人校验；为空则不校验。<b>不匹配时拒绝完成</b>，
     *                          因为完成他人的任务会把业务推进到一个没人负责的状态
     */
    @Transactional
    public Map<String, Object> completeTask(String processId, String taskDefinitionKey, String assignee,
                                            Map<String, Object> variables) {
        org.flowable.task.api.TaskQuery query = tasks.createTaskQuery().processInstanceId(processId);
        if (notBlank(taskDefinitionKey)) query = query.taskDefinitionKey(taskDefinitionKey);
        Task task = query.singleResult();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        if (task == null) {
            result.put("completed", false);
            result.put("reason", "NO_TASK");
            return result;
        }
        if (notBlank(assignee) && !assignee.equals(task.getAssignee())) {
            result.put("completed", false);
            result.put("reason", "ASSIGNEE_MISMATCH");
            result.put("assignee", task.getAssignee());
            return result;
        }
        tasks.complete(task.getId(), variables == null ? Collections.<String, Object>emptyMap() : variables);
        result.put("completed", true);
        result.put("taskId", task.getId());
        result.put("taskName", task.getName());
        return result;
    }

    /**
     * 查询待办任务，供坐席端"取最新一条待接业务"这类轮询使用。
     *
     * <p>返回里带整张流程变量表：调用方拿到 processId 后几乎总要再读一次变量来渲染界面，
     * 分成两次 HTTP 只会让轮询的往返数翻倍。</p>
     *
     * @param order {@code asc} 或 {@code desc}，按任务创建时间
     * @param limit 取前几条；0 表示不限
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> tasks(String taskDefinitionKey, String order, int limit) {
        org.flowable.task.api.TaskQuery query = tasks.createTaskQuery().active();
        if (notBlank(taskDefinitionKey)) query = query.taskDefinitionKey(taskDefinitionKey);
        query = "desc".equalsIgnoreCase(order)
                ? query.orderByTaskCreateTime().desc()
                : query.orderByTaskCreateTime().asc();
        List<Task> found = limit > 0 ? query.listPage(0, limit) : query.list();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Task task : found) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("processId", task.getProcessInstanceId());
            row.put("taskId", task.getId());
            row.put("nodeId", task.getTaskDefinitionKey());
            row.put("nodeName", task.getName());
            row.put("assignee", task.getAssignee());
            row.put("vars", runtime.getVariables(task.getProcessInstanceId()));
            rows.add(row);
        }
        return rows;
    }

    /** 挂起实例：数字人逻辑与流转一并锁定。实例不存在或已结束返回 404。 */
    @Transactional
    public Map<String, Object> suspend(String processId) {
        requireRunning(processId);
        runtime.suspendProcessInstanceById(processId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        result.put("suspended", true);
        return result;
    }

    /**
     * 恢复实例。
     *
     * <p><b>已处于活动状态时按成功处理</b>：调用方（坐席授权完成）会无条件先激活一次，
     * 因为挂起与否取决于此前是否走过风控挂起分支，调用方并不清楚 ——
     * 让它为此先查一次状态，只是把一个幂等操作拆成两次往返。
     * 实例不存在或已结束才是错误。</p>
     */
    @Transactional
    public Map<String, Object> activate(String processId) {
        ProcessInstance instance = requireRunning(processId);
        if (instance.isSuspended()) runtime.activateProcessInstanceById(processId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        result.put("activated", true);
        return result;
    }

    /**
     * 环境复位：删除全部运行中实例。
     *
     * <p>只删运行中的（含其历史），已结束的历史记录保留 —— 交易情况列表要读它们，
     * 清掉等于把列表一起清空。</p>
     *
     * <p>返回被删实例的 processId 列表：这些实例在路由服务里可能还挂着工单，
     * 调用方需要拿这份名单去逐个收口 —— 名单只能由本服务给出，
     * 因为实例一旦删除就再也查不到"刚才删了哪些"。</p>
     */
    /**
     * 全部运行中实例标识。
     *
     * <p>与 {@link #reset()} 分开，是因为环境复位必须"先收口、后删除"：
     * 调用方得先拿到名单去路由服务逐个收口工单，收口成了再删实例。
     * 反过来先删，收口一旦失败就留下一批指向已删流程的工单，
     * 路由服务会一直认为坐席被它们占着。</p>
     */
    @Transactional(readOnly = true)
    public List<String> runningProcessIds() {
        List<String> processIds = new ArrayList<String>();
        for (ProcessInstance instance : runtime.createProcessInstanceQuery().list()) {
            processIds.add(instance.getId());
        }
        return processIds;
    }

    @Transactional
    public Map<String, Object> reset() {
        List<ProcessInstance> running = runtime.createProcessInstanceQuery().list();
        List<String> processIds = new ArrayList<String>();
        for (ProcessInstance instance : running) processIds.add(instance.getId());
        for (ProcessInstance instance : running) runtime.deleteProcessInstance(instance.getId(), "环境复位");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("closed", processIds.size());
        result.put("processIds", processIds);
        return result;
    }

    /**
     * 实例是否仍在运行。
     *
     * <p>单独给一个判活接口，而不是让调用方读一次变量表再从 404 反推：
     * 进线时"该新建还是该更新"这条分支只关心存在性，
     * 为回答一个是非问把整张变量表搬过网络是浪费。</p>
     */
    @Transactional(readOnly = true)
    public boolean isRunning(String processId) {
        return runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult() != null;
    }

    private ProcessInstance requireRunning(String processId) {
        ProcessInstance instance = runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult();
        if (instance == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程实例不存在或已结束");
        return instance;
    }

    private boolean notBlank(String value) { return value != null && !value.trim().isEmpty(); }
}
