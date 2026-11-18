package com.example.bank.routing.routing;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排队路由服务的对外接口。
 *
 * <p>前缀统一为 {@code /internal}：这些能力只供业务逻辑服务与流程引擎服务调用，
 * 不经浏览器直连 —— 客户与坐席看到的接口面由业务逻辑服务统一提供，
 * 避免同一份队列状态出现两个外部入口。</p>
 */
@RestController
@RequestMapping("/internal")
public class RoutingController {

    private final QueueRoutingService routing;
    private final AgentRegistry agents;
    private final DigitalHumanRegistry digitalHumans;
    private final AgentStatsService agentStats;

    public RoutingController(QueueRoutingService routing, AgentRegistry agents, DigitalHumanRegistry digitalHumans,
                             AgentStatsService agentStats) {
        this.routing = routing;
        this.agents = agents;
        this.digitalHumans = digitalHumans;
        this.agentStats = agentStats;
    }

    /** 入队 / 改派：数字人排队、转人工、交还数字人三个流程节点都调这里。 */
    @PostMapping("/route")
    public QueueTicket route(@RequestBody QueueRequest request) {
        return routing.route(request);
    }

    @PostMapping("/ticket/{processId}/answer")
    public QueueTicket answer(@PathVariable String processId, @RequestBody Map<String, Object> body) {
        // 字段缺失如实 400：早先缺字段会被折算成空串，再撞上"归属不符"分支回 409，
        // 调用方看到的是"来电已失效"，实际原因是请求本身没带坐席标识。
        String agentId = requiredNonBlank(body, "agentId");
        String peerId = required(body, "peerId");
        return routing.answer(agentId, processId, peerId);
    }

    @PostMapping("/ticket/{processId}/reject")
    public QueueTicket reject(@PathVariable String processId, @RequestBody Map<String, Object> body) {
        return routing.reject(requiredNonBlank(body, "agentId"), processId);
    }

    @PostMapping("/ticket/{processId}/release")
    public ResponseEntity<Void> release(@PathVariable String processId,
                                        @RequestParam(required = false) String reason) {
        routing.release(processId, reason);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/ticket/{processId}/complete")
    public ResponseEntity<Void> complete(@PathVariable String processId) {
        routing.complete(processId);
        return ResponseEntity.ok().build();
    }

    /** 坐席掉线：由信令的连接事件或业务侧的心跳停滞判定触发。实现是幂等的。 */
    @PostMapping("/agent/{agentId}/offline")
    public ResponseEntity<Void> agentOffline(@PathVariable String agentId) {
        agents.setStatus(agentId, "OFFLINE");
        routing.onAgentOffline(agentId);
        return ResponseEntity.ok().build();
    }

    /**
     * 坐席签入 / 状态变更。上线状态只能经此回写，信令只承担下行推送。
     *
     * <p>状态变化后的连带处理（上线即尝试派单、离线即回收名下工单）在<b>本服务内</b>完成：
     * 派单规则与工单状态机都在这里，让调用方拿着新状态再决定要不要触发，等于把同一条规则
     * 拆到两个服务里各写一半 —— 漏掉一侧的表现是坐席签入后队列纹丝不动。</p>
     *
     * @param body {@code name/peerId/status} 可选 {@code skills}；
     *             <b>技能组为空串表示不改动既有技能组</b>（坐席端在未配置技能时
     *             上报的就是空串，不能因此把它重置成默认值）
     */
    @PostMapping("/agent/{agentId}/status")
    public Map<String, Object> agentStatus(@PathVariable String agentId, @RequestBody Map<String, Object> body) {
        String raw = text(body.get("skills"));
        List<String> skills = raw.trim().isEmpty()
                ? Collections.<String>emptyList()
                : Arrays.asList(raw.split(","));
        // 状态取值域先于注册校验，避免非法状态在名册上留下注册痕迹后才被拒绝。
        String status = text(body.get("status"));
        AgentRegistry.requireValidStatus(status);
        agents.register(agentId, text(body.get("name")), text(body.get("peerId")), skills);
        AgentRegistry.Agent agent = agents.setStatus(agentId, status);
        if ("ONLINE".equals(agent.status)) routing.tryRoute();
        if ("OFFLINE".equals(agent.status)) routing.onAgentOffline(agentId);
        return routing.summary(agentId);
    }

    /** 坐席心跳：只登记活性，不改变状态。回执留给业务逻辑服务补充指令。 */
    @PostMapping("/agent/{agentId}/touch")
    public ResponseEntity<Void> touch(@PathVariable String agentId) {
        agents.touch(agentId);
        return ResponseEntity.ok().build();
    }

    /**
     * 登记一次交易成功，供坐席端「当日交易成功数」统计。
     *
     * <p>由流程服务在流程正常走完时调用：只有它知道流程是否真的结束，
     * 也只有它知道该次提交是不是坐席做的（客户自助与数字人纯自助不归属任何坐席）。
     * 坐席名册是本服务的独占状态，流程服务不持有它的副本，因此这个计数必须回到这里记。</p>
     *
     * <p>坐席不存在时静默返回成功：坐席可能刚好在这一刻掉线或被清理，
     * 而这次交易确实已经完成 —— 为一个统计数字让流程服务的调用失败是本末倒置。</p>
     */
    @PostMapping("/agent/{agentId}/success")
    public ResponseEntity<Void> success(@PathVariable String agentId) {
        agents.recordSuccess(agentId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/agent/{agentId}/calls")
    public Map<String, Object> calls(@PathVariable String agentId) {
        return routing.summary(agentId);
    }

    /**
     * 坐席当日统计（签入 / 小休 / 办理时长与服务量）。
     *
     * <p>换算放在这里而不是调用方：计时字段是名册独占的事实，与状态机的写法一一对应，
     * 调用方照着一份镜像字段复刻规则，规则一改就会分叉 —— 而分叉只表现为时长数字对不上，不报错。</p>
     */
    @GetMapping("/agent/{agentId}/stats/today")
    public Map<String, Object> agentStatsToday(@PathVariable String agentId) {
        return agentStats.today(agentId);
    }

    @GetMapping("/ticket/{processId}")
    public ResponseEntity<QueueTicket> ticket(@PathVariable String processId) {
        QueueTicket ticket = routing.byProcess(processId);
        return ticket == null ? ResponseEntity.status(HttpStatus.NOT_FOUND).build() : ResponseEntity.ok(ticket);
    }

    @GetMapping("/agents")
    public List<AgentRegistry.Agent> agents() {
        return agents.all();
    }

    /** 数字人名册与占用状态，供联调观察"路由给空闲数字人"是否生效。 */
    @GetMapping("/digital-humans")
    public List<DigitalHumanRegistry.DigitalHuman> digitalHumans() {
        return digitalHumans.all();
    }

    @GetMapping("/queue")
    public Map<String, Object> queue(@RequestParam(required = false) String skillGroup) {
        // 只取一次：waiting() 每次调用都重新遍历工单表，两次调用会拿到两份等价但不同的快照，
        // 极端情况下（正好有工单进出）还会让 size 与 waiting 对不上。
        List<QueueTicket> waiting = routing.waiting(skillGroup);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("waiting", waiting);
        result.put("size", waiting.size());
        return result;
    }

    /** 资源健康快照：两条掉线判据（信令连接事件、心跳停滞）的观察入口。 */
    @GetMapping("/health/resources")
    public Map<String, Object> resourceHealth() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("agents", agents.all().size());
        result.put("online", agents.all().stream().filter(a -> !"OFFLINE".equals(a.status)).count());
        result.put("digitalHumans", digitalHumans.all().size());
        result.put("tickets", routing.waiting(null).size());
        return result;
    }

    /** 必填字段：键必须存在；值允许为空串（如未配置 peerId 的坐席）。 */
    private String required(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少必填字段：" + field);
        }
        return String.valueOf(value);
    }

    /** 必填且非空白字段：空串/纯空白视为缺失，不再折算后混入下游的业务冲突错误。 */
    private String requiredNonBlank(Map<String, Object> body, String field) {
        String value = required(body, field);
        if (value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必填字段不能为空：" + field);
        }
        return value;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
