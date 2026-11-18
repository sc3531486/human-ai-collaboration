package com.example.bank.workflow;

import com.example.bank.workflow.events.NodeEventService;
import com.example.bank.workflow.framework.NodeSubmission;
import com.example.bank.workflow.framework.NodeSubmissionService;

import com.example.bank.workflow.session.BusinessSessionService;
import com.example.bank.workflow.signaling.CallSignalingService;
import com.example.bank.workflow.sop.ProcessControlService;
import com.example.bank.workflow.sop.TrailService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程引擎的内部接口面：只供其他服务调用，不经浏览器直连。
 *
 * <p>本服务是 Flowable 的唯一宿主。谁要推进流程、读节点、认领会话，都得走这里 ——
 * 其他服务不持有流程数据库，也不认识 BPMN。</p>
 *
 * <p>接口形状由<b>调用方</b>声明（见 CONTRACTS.md）：routing-svc 的 {@code HttpProcessAdapter}
 * 声明了节点判活、写变量、认领会话；media-svc 声明了变量读写。本类照此实现。</p>
 */
@RestController
@RequestMapping("/internal")
public class WorkflowController {

    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.workflow.framework.FlowData data;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final RepositoryService repository;
    private final BusinessSessionService sessions;
    private final NodeEventService events;
    private final ProcessControlService control;
    private final TrailService trail;
    private final NodeSubmissionService submissions;
    private final CallSignalingService calls;

    public WorkflowController(RuntimeService runtime, HistoryService history, RepositoryService repository,
                              BusinessSessionService sessions, NodeEventService events,
                              ProcessControlService control, TrailService trail,
                              NodeSubmissionService submissions, CallSignalingService calls) {
        this.runtime = runtime;
        this.history = history;
        this.repository = repository;
        this.sessions = sessions;
        this.events = events;
        this.control = control;
        this.trail = trail;
        this.submissions = submissions;
        this.calls = calls;
    }

    /**
     * 启动流程实例。
     *
     * <p>做成通用启动口而不是"进线专用"：拆分前有五个调用点各自直接调 {@code startProcessInstanceByKey}
     * （进线建单、转账受理、余额查询、SOP 演示、数字人自助），它们全部迁去了 business-svc。
     * 给每个场景开一个专用接口，等于把流程定义的形状泄漏到 business-svc，两边一起改才能加一条流程。</p>
     *
     * @param body {@code processDefinitionKey}、可选 {@code businessKey}、{@code variables}
     */
    @PostMapping("/process/start")
    public Map<String, Object> start(@RequestBody Map<String, Object> body) {
        String key = text(body.get("processDefinitionKey"));
        if (key.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少流程定义标识");
        // 不再从 SceneRegistry 读 defaults，直接使用传入的 variables
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.putAll(variablesOf(body.get("variables")));
        Map<String,Object> payload = data.object(body.containsKey("payload") ? body.get("payload") : variables.get("payload"));
        // 不再从 SceneRegistry 读 fields，直接使用传入的 payload
        variables.put("payload", data.encode(payload));
        variables.put("results", "{}");
        variables.put("submission_revision", 0L);
        String businessKey = text(body.get("businessKey"));
        // 两种启动方式在拆分前并存，此处按 businessKey 是否给出选择，保持与调用方一致的行为
        ProcessInstance instance = businessKey.isEmpty()
                ? runtime.startProcessInstanceByKey(key, variables)
                : runtime.startProcessInstanceByKey(key, businessKey, variables);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", instance.getProcessInstanceId());
        result.put("processDefinitionKey", instance.getProcessDefinitionKey());
        result.put("processDefinitionId", instance.getProcessDefinitionId());
        result.put("ended", instance.isEnded());
        return result;
    }

    /**
     * 读取整张流程变量表。
     *
     * <p>实例不存在时<b>必须返回 4xx，不能返回空表</b>：media-svc 靠这个区分决定是否下发房间号，
     * 静默返回空表会让它把"流程已经没了"当成"变量恰好都是空的"，继续发出一个并未持久化的房间号。</p>
     */
    @GetMapping("/process/{processId}/variables")
    public Map<String, Object> variables(@PathVariable String processId) {
        if (runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程实例不存在或已结束");
        }
        return data.project(runtime.getVariables(processId));
    }

    /** 写入流程变量；同样要求实例存在，理由见 {@link #variables}。 */
    @PostMapping("/process/{processId}/variables")
    public Map<String, Object> setVariables(@PathVariable String processId,
                                            @RequestBody Map<String, Object> variables) {
        if (runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程实例不存在或已结束");
        }
        runtime.setVariables(processId, variables == null ? Collections.<String, Object>emptyMap() : variables);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        result.put("written", variables == null ? 0 : variables.size());
        return result;
    }

    /**
     * 判断某流程实例当前是否停在指定节点。
     *
     * <p>这里要区分两种"不在运行时"：<b>流程已结束</b>与<b>实例根本不存在</b>。</p>
     *
     * <p>已结束要返回 {@code active:false} —— 这是 routing-svc 收口工单的正常路径，
     * 返回 4xx 会走进它的"查询失败按活跃处理"分支，工单再也收不了口。
     * 而实例不存在返回 404，让那次查询失败被如实当成失败。</p>
     */
    @GetMapping("/process/{processId}/node/{activityId}/active")
    public Map<String, Object> nodeActive(@PathVariable String processId, @PathVariable String activityId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult() == null) {
            if (history.createHistoricProcessInstanceQuery().processInstanceId(processId).singleResult() == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流程实例不存在");
            }
            result.put("active", false);
            result.put("reason", "ENDED");
            return result;
        }
        result.put("active", runtime.getActiveActivityIds(processId).contains(activityId));
        return result;
    }

    /**
     * 坐席认领人工会话：把归属写进流程变量。
     *
     * <p>{@code BusinessSessionService.claim} 已经用 409 表达"已离开人工节点"与"已被他人接听"，
     * 这里原样透出，不包装成 500 —— routing-svc 靠 4xx 与 5xx 的区别转成前端的提示文案。</p>
     */
    @PostMapping("/process/{processId}/session/claim")
    public Map<String, Object> claim(@PathVariable String processId, @RequestBody Map<String, Object> body) {
        sessions.claim(processId, text(body.get("agentId")), text(body.get("peerId")));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        result.put("claimed", true);
        return result;
    }

    /** 接听异常清理：校验归属后释放本次认领，不终止业务流程。 */
    @PostMapping("/process/{processId}/session/release")
    public void releaseClaim(@PathVariable String processId, @RequestBody Map<String,Object> input) {
        sessions.releaseClaim(processId,text(input.get("agentId")));
    }

    @PostMapping("/process/{processId}/node/{activityId}/trigger")
    public Map<String, Object> trigger(@PathVariable String processId, @PathVariable String activityId,
                                       @RequestBody(required = false) Map<String, Object> variables) {
        return control.trigger(processId, activityId, variables);
    }

    /**
     * 完成待办任务。请求体 {@code {taskDefinitionKey?, assignee?, variables?}}。
     *
     * <p>无待办时返回 {@code completed:false}（不是 4xx）：调用方普遍把它当幂等成功。</p>
     */
    @PostMapping("/process/{processId}/task/complete")
    public Map<String, Object> completeTask(@PathVariable String processId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        return control.completeTask(processId, text(input.get("taskDefinitionKey")), text(input.get("assignee")),
                variablesOf(input.get("variables")));
    }

    /**
     * 待办任务查询：供坐席端"取最新一条待接业务"这类轮询。
     *
     * <p>返回里带整张流程变量表 —— 调用方拿到 processId 后总还要读变量渲染界面，
     * 分两次 HTTP 只会让轮询的往返数翻倍。</p>
     */
    @GetMapping("/process/tasks")
    public List<Map<String, Object>> tasks(@RequestParam(required = false) String taskDefinitionKey,
                                           @RequestParam(required = false) String order,
                                           @RequestParam(defaultValue = "0") int limit) {
        return control.tasks(taskDefinitionKey, order, limit);
    }

    /** 实例是否仍在运行。进线时"该新建还是该更新流程"的分支据此判定。 */
    @GetMapping("/process/{processId}/running")
    public Map<String, Object> running(@PathVariable String processId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        result.put("running", control.isRunning(processId));
        return result;
    }

    /** 挂起实例：数字人逻辑与流转一并锁定。 */
    @PostMapping("/process/{processId}/suspend")
    public Map<String, Object> suspend(@PathVariable String processId) {
        return control.suspend(processId);
    }

    /** 恢复实例。已处于活动状态时按成功处理，调用方无需先查状态。 */
    @PostMapping("/process/{processId}/activate")
    public Map<String, Object> activate(@PathVariable String processId) {
        return control.activate(processId);
    }

    /**
     * 环境复位：删除全部运行中实例，返回被删的 processId 名单。
     *
     * <p>名单必须由本服务给出 —— 实例删除后就再也查不到"刚才删了哪些"，
     * 而调用方要拿它去收口路由服务里可能还挂着的工单。</p>
     */
    @PostMapping("/process/reset")
    public Map<String, Object> reset() {
        return control.reset();
    }

    /**
     * 全部运行中实例标识。
     *
     * <p>环境复位分两步做：先按这份名单收口路由服务里的工单，再调 {@code /process/reset} 删实例。
     * 顺序不能反 —— 删完再收口，收口一旦失败就留下一批指向已删流程的工单，
     * 而路由服务会一直认为坐席被它们占着。</p>
     */
    @GetMapping("/process/running")
    public List<String> running() {
        return control.runningProcessIds();
    }

    /** 某坐席可见的人工节点待办：未认领的对所有人可见，已认领的只对归属坐席可见。 */
    @GetMapping("/process/session/queue")
    public List<Map<String, Object>> sessionQueue(@RequestParam String agentId) {
        return sessions.queue(agentId);
    }

    /**
     * 校验业务会话归属。
     *
     * <p>做成"会失败的操作"而不是让调用方读 {@code agent_session_id} 自己比：
     * "谁拥有这单"这条规则属于持有该变量的服务，泄漏出去会让两边各判一次。</p>
     */
    @PostMapping("/process/{processId}/session/require-owner")
    public Map<String, Object> requireOwner(@PathVariable String processId, @RequestBody Map<String, Object> body) {
        sessions.requireOwner(processId, text(body.get("agentId")));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        result.put("owned", true);
        return result;
    }

    /** 坐席结束业务：终止实例并释放归属。幂等，流程已结束时返回 {@code closed:false}。 */
    @PostMapping("/process/{processId}/session/close")
    public Map<String, Object> closeSession(@PathVariable String processId) {
        return sessions.close(processId);
    }

    /**
     * 节点快照：供 business-svc 转发给页面，也是节点推送的校准入口。
     *
     * <p><b>必须走 {@code NodeEventService} 而不是直接调 {@code SopProcessService}</b>：
     * 前者维护该流程最近一次推送出去的内容，内容有变化才经信令服务推送；
     * 直接读快照能拿到一样的内容，但绕过了去重，页面订阅端会收到重复事件，
     * 且本服务无从判断"这条到底变没变"。节点的实时推送另由引擎事件驱动
     * （{@code NodeAdvanceListener}），与本接口无关。</p>
     *
     * <p>拆分前每一个读快照的接口（工作台 state / accept / call、余额 broadcast-complete / status、
     * 进线返回 state）都经这个入口，此处保持一致。</p>
     */
    @GetMapping("/process/{processId}/state")
    public Map<String, Object> state(@PathVariable String processId) {
        return events.snapshot(processId);
    }

    /** 流程轨迹：按历史节点回放结构化载荷，供坐席端 1:1 只读还原当时界面。 */
    @GetMapping("/process/{processId}/trail")
    public Map<String, Object> trail(@PathVariable String processId) {
        return trail.restore(processId);
    }

    /** 某流程实例的节点提交历史：已提交过哪些动作、由谁、提交了什么。 */
    @GetMapping("/process/{processId}/submissions")
    public List<Map<String, Object>> submissions(@PathVariable String processId) {
        return submissions.history(processId);
    }

    /**
     * 节点提交：幂等检查、节点与归属校验、字段校验、登记、场景钩子、流程推进。
     *
     * <p>整条管线必须在同一事务里 —— 登记与流程变更分属两次写入，
     * 中间失败会留下一条"已提交但流程没动"的记录，重试又被幂等挡下，业务就此卡死。</p>
     *
     * @return {@code {replayed}}：命中同一 requestId 的重复提交返回 true 且不重复推进。
     *         调用方据此区分"刚提交成功"与"这次是重试"，但它本身不是错误
     */
    @PostMapping({"/process/{processId}/submissions", "/process/{processId}/submit"})
    public Map<String, Object> submit(@PathVariable String processId, @RequestBody NodeSubmission input) {
        boolean replayed = submissions.submit(processId, input);
        boolean ended = runtime.createProcessInstanceQuery().processInstanceId(processId).singleResult() == null;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        result.put("replayed", replayed);
        result.put("ended", ended);
        return result;
    }

    /**
     * 坐席切换通话模式：{@code av} / {@code audio} / {@code released}。
     *
     * <p>先校验归属再由本服务写入 —— 通话状态与业务会话是同一份变量表上的两件事，
     * 拆成"先校验再写"由调用方各发一次请求，中间可能被别的坐席抢走归属。
     * 归属不符返回 409，模式非法返回 400。</p>
     */
    @PostMapping("/process/{processId}/call")
    public Map<String, Object> call(@PathVariable String processId, @RequestBody Map<String, Object> body) {
        calls.update(processId, text(body.get("agentId")), text(body.get("mode")));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("processId", processId);
        result.put("updated", true);
        return result;
    }

    /** 联调观察用：确认流程定义加载情况，避免"接口在但流程没部署"被误判为接口故障。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("service", "workflow-svc");
        result.put("processDefinitions", repository.createProcessDefinitionQuery().count());
        result.put("runningInstances", runtime.createProcessInstanceQuery().count());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> variablesOf(Object raw) {
        // 未携带 variables 视为"无额外变量"；携带了却不是对象属于畸形请求，400 拒绝而不是当空表吞掉
        if (raw == null) return Collections.<String, Object>emptyMap();
        if (!(raw instanceof Map)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "variables 必须是 JSON 对象");
        return (Map<String, Object>) raw;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
