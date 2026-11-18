package com.example.bank.business.controller;

import com.example.bank.business.port.AgentPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 坐席在线判据：上行心跳登记 + 掉线事件消费。
 *
 * <p><b>信令负责"发现沉默"，这里负责"理解沉默的含义"</b>。两条判据覆盖的故障模式不同：
 * 连接事件（信令侧 presence 归零）抓的是正常关闭与强杀，抓不到坐席端 JS 卡死 ——
 * 那时 socket 仍连着；心跳停滞抓的正是后者，但 Centrifugo 自己挂掉时它反而是唯一有效的判据。
 * 两者汇合到同一个处理入口。</p>
 *
 * <p>本类没有类级 {@code @RequestMapping}：入站事件来自信令服务的内部接口面，
 * 心跳来自坐席端的对外接口面，一个类同时服务两侧。分开写前缀的代价是
 * 让"同一个故障的两端"落在两个文件里，改动时容易只改一侧。</p>
 */
@RestController
@EnableScheduling
public class PresenceController {

    private static final Logger log = LoggerFactory.getLogger(PresenceController.class);

    /** 事件信封的去重保留时长：重复投递只发生在秒级窗口内，留 10 分钟绰绰有余。 */
    private static final long DEDUP_TTL_MS = 10 * 60 * 1000L;

    private final AgentPort agents;
    private final long heartbeatTimeoutMs;
    private final long heartbeatIntervalMs;

    /** 坐席最近一次心跳时刻。只记时间戳，不记在线状态 —— 状态由路由服务的名册独占。 */
    private final Map<String, Long> heartbeats = new ConcurrentHashMap<String, Long>();

    /** 已处理事件信封：键为 {@code agentId@at}。 */
    private final Map<String, Long> seen = new ConcurrentHashMap<String, Long>();

    public PresenceController(AgentPort agents,
                              @Value("${presence.heartbeat-timeout-ms:45000}") long heartbeatTimeoutMs,
                              @Value("${presence.heartbeat-interval-ms:15000}") long heartbeatIntervalMs) {
        this.agents = agents;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    /**
     * 坐席端心跳上报。
     *
     * <p>顺带把活性转发给路由服务：它的"最早空闲优先"排序按最后活跃时间取人，
     * 那个时间戳与这里记的是同一件事，分开各记就会分叉。</p>
     *
     * <p>回执里带上建议的上报周期：周期与超时阈值是一对必须配套的参数
     * （周期接近阈值时，一次丢包就会被判成掉线），所以由服务端给出而不是前端各写一个常量。</p>
     */
    @PostMapping("/api/agent/heartbeat")
    public Map<String, Object> heartbeat(@RequestBody Map<String, Object> payload) {
        String agentId = text(payload.get("agentId"));
        if (agentId.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少坐席标识");
        heartbeats.put(agentId, System.currentTimeMillis());
        agents.touch(agentId);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("intervalMs", heartbeatIntervalMs);
        return result;
    }

    /**
     * 掉线事件消费：信令的连接事件与本地心跳停滞共用这一个入口。
     *
     * <p>请求体即 CONTRACTS.md 登记的事件信封
     * （{@code domain/type/agentId/at/reason/source/channel}）。</p>
     *
     * <p>只做两件事：登记观察到的事实、把它变成状态变更。含义的判定权在这里 ——
     * 信令服务不认识坐席名册，也不认识工单，它只报告"这个频道上没有连接了"。</p>
     */
    @PostMapping("/internal/presence/disconnected")
    public Map<String, Object> disconnected(@RequestBody Map<String, Object> event) {
        return handle(text(event.get("agentId")), longOf(event.get("at")),
                text(event.get("reason")), text(event.get("source")));
    }

    /**
     * 产品事件入口复用：把"连接关闭"这一通用事实翻译成坐席离线。
     * 信令产品不再直接上报 {@code AGENT_DISCONNECTED}，由本服务决定其业务含义。
     */
    public Map<String, Object> onAgentDisconnected(String agentId, long at, String reason, String source) {
        return handle(agentId, at, reason, source);
    }

    /**
     * 应用层沉默扫描。
     *
     * <p><b>只扫描有过心跳记录的坐席</b>：从没上报过心跳的坐席（前端尚未接入心跳、
     * 或从未打开过工作台）不在判定范围内。若把"没有记录"也当成超时，
     * 这个定时任务会在启动后 45 秒内把整个名册判成掉线。</p>
     */
    @Scheduled(fixedDelay = 5000)
    public void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : heartbeats.entrySet()) {
            if (now - entry.getValue() <= heartbeatTimeoutMs) continue;
            // 带值移除：期间若被心跳刷新过，说明坐席还活着，这一轮不该动它
            if (!heartbeats.remove(entry.getKey(), entry.getValue())) continue;
            handle(entry.getKey(), now, "HEARTBEAT_TIMEOUT", "BUSINESS_HEARTBEAT_TIMEOUT");
        }
    }

    /**
     * 两条判据的汇合点。
     *
     * <p>去重键是 {@code agentId + at}，只拦住<b>同一个信封</b>的重复投递。
     * 两条判据先后触发时 {@code at} 不同，会各走一次 —— 这是有意的：
     * 路由侧的掉线处理是幂等的（名下工单第一次处理后不再指向该坐席），
     * 重复调用不会重复扣减服务量，而"哪条判据先发现"本身是值得留下的事实。</p>
     */
    private Map<String, Object> handle(String agentId, long at, String reason, String source) {
        if (agentId.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少坐席标识");
        boolean recorded = firstSighting(agentId, at);
        if (recorded) {
            log.warn("坐席掉线：agentId={}，reason={}，source={}", agentId, reason, source);
        }
        // 摘掉本地心跳登记：不清掉的话，下一轮扫描会拿同一个旧时间戳再判一次。
        // 坐席若已经恢复上报，这里删掉的记录会在下次心跳时立刻重建。
        heartbeats.remove(agentId);
        agents.offline(agentId);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accepted", true);
        result.put("recorded", recorded);
        return result;
    }

    /** 记录事件信封并回答"是不是第一次见到它"。顺带清掉过期的去重键。 */
    private boolean firstSighting(String agentId, long at) {
        long now = System.currentTimeMillis();
        seen.values().removeIf(timestamp -> now - timestamp > DEDUP_TTL_MS);
        return seen.putIfAbsent(agentId + "@" + at, now) == null;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private long longOf(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        // 事件信封的 at 是必填字段（CONTRACTS.md 事件信封）；缺失即上报方缺陷，不以当前时间悄悄补值
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件缺少时间戳 at");
    }
}
