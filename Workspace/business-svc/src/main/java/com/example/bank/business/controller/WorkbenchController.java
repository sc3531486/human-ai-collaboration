package com.example.bank.business.controller;

import com.example.bank.business.application.BusinessApplicationService;
import com.example.bank.business.contract.Agent;
import com.example.bank.business.contract.DigitalHuman;
import com.example.bank.business.contract.QueueTicket;
import com.example.bank.business.port.AgentPort;
import com.example.bank.business.port.ProcessPort;
import com.example.bank.business.port.RoutingPort;
import com.example.bank.business.port.SessionPort;
import com.example.bank.business.service.BizRecordService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 工作台入口。
 *
 * <p>本类是本服务里<b>转发面最大</b>的一处：队列、流程节点、会话归属、通话状态、坐席名册
 * 全部由别的服务持有，这里只做三件事 —— 转发调用、拼装返回、把多个下游结果合并成
 * 前端一次请求就能渲染的一份数据。凡是本服务自己就是事实源的（客户建档、业务记录、
 * 通知、交易目录）都不在这里，而在各自的服务类里。</p>
 *
 * <p>合并返回是这里唯一"不是纯转发"的职责：坐席接听一次调用要拿到
 * 最新业务快照、工单、界面还原载荷三样，拆成三个接口会让前端多两次往返，
 * 且中间流程可能已经推进，拼出来的现场自相矛盾。</p>
 */
@RestController
@RequestMapping({"/api/platform", "/api/workbench"})
public class WorkbenchController {
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.service.AgentLoginService accounts;
    @org.springframework.beans.factory.annotation.Autowired private com.example.bank.business.evidence.NodeEvidenceService evidence;
    private final BusinessApplicationService transfers;
    private final ProcessPort process;
    private final SessionPort sessions;
    private final RoutingPort routing;
    private final AgentPort agents;
    private final BizRecordService records;

    public WorkbenchController(BusinessApplicationService transfers, ProcessPort process, SessionPort sessions,
                               RoutingPort routing, AgentPort agents, BizRecordService records) {
        this.transfers = transfers;
        this.process = process;
        this.sessions = sessions;
        this.routing = routing;
        this.agents = agents;
        this.records = records;
    }

    @GetMapping("/queue")
    public List<Map<String, Object>> queue(@RequestParam String agentId) { return sessions.queue(agentId); }

    /** 场景配置：节点页面名、提交动作与字段策略。原样转发，本服务不解析。 */
    @GetMapping("/scenes")
    public List<Map<String, Object>> scenes() { return process.scenes(); }

    @GetMapping("/process/{id}/submissions")
    public List<Map<String, Object>> submissions(@PathVariable String id) { return process.submissions(id); }

    /**
     * 节点通用提交接口。
     *
     * <p>支持大字段透传：表单字段与业务要素整体传入，流程引擎负责保存大字段并推进节点。
     * 业务服务在流程完成（COMPLETED）时，在本地闭环更新业务台账与坐席指标，无需流程引擎反写。</p>
     */
    @PostMapping({"/process/{id}/actions", "/processes/{id}/submit", "/process/{id}/submit"})
    public Map<String, Object> submitNode(@PathVariable String id, @RequestBody Map<String, Object> input) {
        evidence.verifySubmission(process.state(id),input);
        boolean replayed = process.submit(id, input);
        Map<String, Object> result = process.state(id);
        result.put("replayed", replayed);

        return result;
    }

    private String extractBizNo(Map<String, Object> result) {
        if (result == null) return "";
        Object bizNoObj = result.get("bizNo");
        if (bizNoObj != null && !String.valueOf(bizNoObj).isEmpty() && !"null".equals(String.valueOf(bizNoObj))) {
            return String.valueOf(bizNoObj);
        }
        Object varsObj = result.get("vars");
        if (varsObj instanceof Map) {
            Object varBiz = ((Map<?, ?>) varsObj).get("biz_no");
            if (varBiz != null && !String.valueOf(varBiz).isEmpty()) {
                return String.valueOf(varBiz);
            }
        }
        return "";
    }

    @GetMapping("/process/{id}")
    public Map<String, Object> state(@PathVariable String id) { return process.state(id); }

    /** 界面还原：按历史节点回放结构化数据载荷，只读回溯不改引擎节点。 */
    @GetMapping("/process/{id}/trail")
    public Map<String, Object> trail(@PathVariable String id) { return process.trail(id); }

    @PostMapping("/process")
    public Map<String, Object> start(@RequestBody Map<String, Object> input) {
        return process.state(transfers.start(input));
    }

    @PostMapping("/process/{id}/accept")
    public Map<String, Object> accept(@PathVariable String id, @RequestBody Map<String, String> input) {
        // 保留旧调用地址，但接听规则统一交给路由服务；不允许绕过工单认领。
        routing.answer(input.get("agentId"), id, input.get("peerId"));
        return process.state(id);
    }

    /**
     * 坐席结束业务：终止流程实例并释放队列工单。
     *
     * <p>不做归属校验 —— 结束的是坐席自己打开的标签页，且 close 本身幂等；
     * 加上归属校验只会让「重连后残留的标签」关不掉，反而重新制造死角。</p>
     *
     * <p>先结束业务会话再收口工单：工单的释放依据是会话归属，顺序反过来会让
     * 收口时读到一份还为该坐席保留着的归属。</p>
     */
    @PostMapping("/process/{id}/close")
    public Map<String, Object> close(@PathVariable String id) {
        Map<String, Object> result = sessions.close(id);
        routing.complete(id);
        return result;
    }

    @PostMapping("/process/{id}/call")
    public Map<String, Object> call(@PathVariable String id, @RequestBody Map<String, String> input) {
        sessions.setCallMode(id, input.get("agentId"), input.get("mode"));
        return process.state(id);
    }

    // ---- 坐席端来电控制：接听 / 拒接 / 队列快照 / 在线状态 / 坐席名册 ----

    /**
     * 接听接口：校验振铃归属后绑定业务，返回最新业务快照用于还原界面。
     * 额外附带界面还原载荷，坐席一次调用即可"打开对应交易 + 还原界面"，
     * 不必再接一次 /process/{id}/trail；trail 只读回放历史节点，不影响引擎当前节点。
     */
    @PostMapping("/call/{id}/answer")
    public Map<String, Object> answer(@PathVariable String id, @RequestBody Map<String, String> input) {
        // 工单取接听动作本身的返回值，而不是接听后再查一次 —— 后者可能已被别的动作改动
        QueueTicket ticket = routing.answer(input.get("agentId"), id, input.get("peerId"));
        Map<String, Object> result = process.state(id);
        result.put("answered", true);
        result.put("ticket", ticket);
        result.put("restore", process.trail(id));
        return result;
    }

    /** 拒接：坐席显式放弃，交给其他坐席继续匹配。 */
    @PostMapping("/call/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id, @RequestBody Map<String, String> input) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("ticket", routing.reject(input.get("agentId"), id));
        return result;
    }

    /** 坐席队列快照：WebSocket 断线时轮询兜底，事件语义与推送一致。 */
    @GetMapping("/agent/{agentId}/calls")
    public Map<String, Object> calls(@PathVariable String agentId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("queue", agents.summary(agentId));
        result.put("flownodes", sessions.queue(agentId));
        return result;
    }

    /**
     * 坐席签入 / 签出：路由只匹配在线坐席。
     *
     * <p>状态变化后的连带处理（上线派单、离线回收工单）全在路由服务内完成 ——
     * 这里只上报"这个人现在是什么状态"。返回的回执里带该坐席此刻的队列摘要，
     * 签入是页面刷新后恢复现场的时刻，那份摘要必须与名册同源。</p>
     */
    @PostMapping("/agent/{agentId}/status")
    public Map<String, Object> agentStatus(@PathVariable String agentId, @RequestBody Map<String, String> input) {
        Map<String,Object> profile = accounts.profile(agentId);
        @SuppressWarnings("unchecked") List<String> skills = (List<String>)profile.get("skills");
        Map<String, Object> summary = agents.setStatus(agentId, String.valueOf(profile.get("name")), input.get("peerId"),
                String.join(",",skills), input.getOrDefault("status", "ONLINE"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("queue", summary);
        return result;
    }

    @GetMapping("/agents")
    public List<Agent> agents() { return agents.all(); }

    /**
     * 坐席当日统计：签入 / 小休 / 办理时长与服务量，单位秒。
     * 坐席不存在时返回全 0 默认值，前端始终可渲染。
     */
    @GetMapping("/agent/{agentId}/stats/today")
    public Map<String, Object> agentStatsToday(@PathVariable String agentId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        Map<String,Object> stats = new LinkedHashMap<>(agents.statsToday(agentId));
        stats.put("successCount",records.successCountToday(agentId));
        result.put("data", stats);
        return result;
    }

    /** 数字人名册与占用状态，供联调观察"排队路由给空闲数字人"是否生效。 */
    @GetMapping("/digital-humans")
    public List<DigitalHuman> digitalHumans() { return agents.digitalHumans(); }
}
