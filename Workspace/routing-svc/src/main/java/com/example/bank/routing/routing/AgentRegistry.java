package com.example.bank.routing.routing;

import com.example.bank.routing.persistence.RoutingAgentRepository;
import com.example.bank.routing.persistence.RoutingDailyStatsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 坐席注册表：维护坐席的技能组、在线状态与当前服务量，为队列路由提供匹配依据。
 * 原型为进程内注册表；多实例部署时应替换为共享会话存储。
 */
@Service
public class AgentRegistry {

    public static class Agent implements RoutingResource {
        public String agentId;
        public String name;
        public String peerId = "";
        public Set<String> skills = new LinkedHashSet<String>();
        public String status = "OFFLINE";
        public int serving;
        /** 并发接待容量；当前匹配逻辑按 1 处理，字段先落库供产品化扩展。 */
        public int capacity = 1;
        public long lastActive = System.currentTimeMillis();

        // ---- 按坐席维度的当日计时与计数（进程内累计，服务重启即清零）----
        /** 当前状态的开始时间戳；状态切换时先按"旧状态"结算本段时长，再重置为切换时刻。 */
        public long statusSince = System.currentTimeMillis();
        /** 本次上线开始时间；离线时清零。 */
        public long onlineSince;
        /** 累计签入时长（ONLINE + BUSY，单位毫秒）。 */
        public long onlineMillis;
        /** 累计小休时长（BREAK，单位毫秒）。 */
        public long breakMillis;
        /** 累计办理时长（serving 从 0 变 1 到重新归 0 的整段，单位毫秒）。 */
        public long serviceMillis;
        /** 当前这段办理的开始时间戳；serving 由 0 变正数时记录。 */
        public long servingSince;
        /** 当日已服务次数：每完成一次服务（serving 归 0）加一。 */
        public int servedCount;
        /** 当日交易成功数：由业务侧调用 recordSuccess 累加。 */
        public int successCount;

        public boolean has(String skill) { return skills.contains(skill); }

        public String id() { return agentId; }
        public Set<String> skills() { return skills; }
        public String status() { return status; }
        public int serving() { return serving; }
        public long lastActive() { return lastActive; }
    }

    private final Map<String, Agent> agents = new LinkedHashMap<String, Agent>();

    /**
     * 状态变更记录。默认空实现，单元测试/无库环境不影响路由主流程；
     * Spring 启动时注入 JDBC 实现，绩效查询直接读状态区间表。
     */
    private AgentStatusLog statusLog = AgentStatusLog.NOOP;

    /** 资源属性仓储；单元测试/无库环境为空，不阻断路由主流程。 */
    private RoutingAgentRepository agentRepository;
    private RoutingDailyStatsRepository dailyStatsRepository;

    @Autowired(required = false)
    public void setStatusLog(AgentStatusLog statusLog) {
        if (statusLog != null) {
            this.statusLog = statusLog;
        }
    }

    @Autowired(required = false)
    public void setAgentRepository(RoutingAgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Autowired(required = false)
    public void setDailyStatsRepository(RoutingDailyStatsRepository dailyStatsRepository) {
        this.dailyStatsRepository = dailyStatsRepository;
    }

    /** 状态取值域：与 CONTRACTS.md 中 Agent.status 的取值域一致。 */
    private static final List<String> VALID_STATUS = Arrays.asList("ONLINE", "BUSY", "BREAK", "OFFLINE");

    /**
     * 校验状态取值域：空串 / 缺失表示"不改状态"（{@code status} 是可选字段，见
     * {@link RoutingController#agentStatus} 的接口注释）；非空但不在取值域内一律拒绝。
     * 早先的实现会静默忽略非法状态 —— 请求拿到 200、名册也有该坐席，唯独状态没生效。
     */
    static void requireValidStatus(String status) {
        if (status != null && !status.isEmpty() && !VALID_STATUS.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的坐席状态：" + status);
        }
    }

    // 名册初始为空，人员身份来自业务资源目录或真实签入，不在路由服务预置演示人员。

    public synchronized void updateMetadata(String agentId, String name, Collection<String> skills) {
        Agent existing = agents.get(agentId);
        long lastActive = existing == null ? 0 : existing.lastActive;
        Agent updated = register(agentId, name, null, skills);
        // 目录同步不是坐席心跳，不能延长在线资源的活性。
        if (existing != null) {
            updated.lastActive = lastActive;
            persist(updated);
        }
    }

    public synchronized Agent register(String agentId, String name, String peerId, Collection<String> skills) {
        if (agentId == null || agentId.trim().isEmpty()) throw new IllegalArgumentException("缺少坐席标识");
        Agent agent = agents.get(agentId);
        boolean created = agent == null;
        boolean hasSkills = skills != null && skills.stream().anyMatch(skill -> skill != null && !skill.trim().isEmpty());
        if ((created || (skills != null && !skills.isEmpty())) && !hasSkills) {
            throw new IllegalArgumentException("坐席资源未配置有效技能组");
        }
        if (created) {
            agent = new Agent();
            agent.agentId = agentId;
            agents.put(agentId, agent);
        }
        if (name != null && !name.trim().isEmpty()) agent.name = name.trim();
        if (peerId != null) agent.peerId = peerId;
        if (skills != null && !skills.isEmpty()) {
            agent.skills.clear();
            for (String skill : skills) if (skill != null && !skill.trim().isEmpty()) agent.skills.add(skill.trim());
        }
        if (agent.skills.isEmpty()) throw new IllegalArgumentException("坐席资源未配置技能组");
        if (created) {
            agent.status = "OFFLINE";
            // 首次登记才起点计时：重复签到时保持原起点，否则会丢掉当前状态已走过的这一段。
            agent.statusSince = System.currentTimeMillis();
        }
        agent.lastActive = System.currentTimeMillis();
        persist(agent);
        return agent;
    }

    public synchronized Agent setStatus(String agentId, String status) {
        // 非法状态在产生任何副作用之前拒绝，不再静默忽略（保持旧状态照常返回 200）。
        requireValidStatus(status);
        Agent agent = agents.get(agentId);
        if (agent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "坐席未登记资源属性");
        long now = System.currentTimeMillis();
        if (status != null && !status.isEmpty()) {
            String beforeStatus = agent.status;
            long beforeSince = agent.statusSince;
            // 先按"旧状态"结算本段时长，再重置起点：同一段时长只会被累加一次。
            // ONLINE / BUSY 同属签入桶（BUSY 也是在岗办理中）；BREAK 单独记小休；OFFLINE 不计时。
            long elapsed = now - beforeSince;
            if (elapsed < 0) elapsed = 0;
            if ("ONLINE".equals(beforeStatus) || "BUSY".equals(beforeStatus)) {
                agent.onlineMillis += elapsed;
            } else if ("BREAK".equals(beforeStatus)) {
                agent.breakMillis += elapsed;
            }
            agent.status = status;
            agent.statusSince = now;
            statusLog.change(agentId, beforeStatus, beforeSince, status, now, null, "AGENT");
        }
        agent.lastActive = now;
        if (!"OFFLINE".equals(agent.status) && agent.onlineSince == 0) {
            agent.onlineSince = now;
        } else if ("OFFLINE".equals(agent.status)) {
            agent.onlineSince = 0L;
        }
        persist(agent);
        return agent;
    }

    public synchronized void touch(String agentId) {
        Agent agent = agents.get(agentId);
        if (agent != null) {
            agent.lastActive = System.currentTimeMillis();
            persist(agent);
        }
    }

    public synchronized void addServing(String agentId, int delta) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            // 名册只增不删，取不到只能是不变量被破坏（如工单接待方与名册失同步），如实抛出。
            throw new IllegalStateException("服务量变更指向不存在的坐席：" + agentId);
        }
        long now = System.currentTimeMillis();
        int before = agent.serving;
        if (before + delta < 0) {
            // 服务量不变量（占用与 ANSWERED 严格对应，见 QueueRoutingService 类注释）被破坏时如实抛出。
            // 早先用 Math.max 钳到 0，重复释放被悄悄吞掉，表现为服务量与实际占用对不上、不报任何错。
            throw new IllegalStateException("坐席服务量出现负数，疑似重复释放：agentId=" + agentId + "，delta=" + delta);
        }
        agent.serving = before + delta;
        // 办理时长按"接起（0 -> 正数）到办结（正数 -> 0）"整段结算，只在两端各触发一次，
        // 并发叠加（before 已是正数）不会重复累计；重复释放由上面的负数校验直接拒绝。
        if (delta > 0 && before == 0) {
            agent.servingSince = now;
        } else if (delta < 0 && before > 0 && agent.serving == 0) {
            long elapsed = now - agent.servingSince;
            if (elapsed > 0) agent.serviceMillis += elapsed;
            agent.servedCount++;
        }
        String next = agent.serving > 0 ? "BUSY" : (agent.status.equals("OFFLINE") ? "OFFLINE" : "ONLINE");
        if (!next.equals(agent.status)) {
            // ONLINE 与 BUSY 同属签入桶，这里只重置计时起点，不做跨桶结算；
            // 真正的桶结算仍统一由 setStatus 负责，避免两处重复累加。
            String beforeStatus = agent.status;
            long beforeSince = agent.statusSince;
            agent.status = next;
            agent.statusSince = now;
            statusLog.change(agentId, beforeStatus, beforeSince, next, now, "SERVING", "SYSTEM");
        }
        agent.lastActive = now;
        persist(agent);
    }

    /** 记录一次交易成功；坐席不存在时静默返回，不影响主流程。 */
    public synchronized void recordSuccess(String agentId) {
        Agent agent = agents.get(agentId);
        if (agent == null) return;
        agent.successCount++;
        persist(agent);
        if (dailyStatsRepository != null) {
            dailyStatsRepository.recordSuccess(agentId);
        }
    }

    /**
     * 启动恢复：只还原字段，不写状态日志、不触发派单。
     */
    public synchronized void restore(String agentId, String name, String peerId, Collection<String> skills,
                                     String status, long statusSince, long onlineSince, long lastActive, int capacity) {
        if (agentId == null || agentId.trim().isEmpty()) return;
        Agent agent = new Agent();
        agent.agentId = agentId;
        agent.name = name == null ? "" : name;
        agent.peerId = peerId == null ? "" : peerId;
        if (skills != null) {
            for (String skill : skills) {
                if (skill != null && !skill.trim().isEmpty()) agent.skills.add(skill.trim());
            }
        }
        agent.status = status == null || status.trim().isEmpty() ? "OFFLINE" : status;
        agent.statusSince = statusSince > 0 ? statusSince : System.currentTimeMillis();
        agent.onlineSince = onlineSince;
        agent.lastActive = lastActive > 0 ? lastActive : agent.statusSince;
        agent.capacity = capacity > 0 ? capacity : 1;
        agents.put(agentId, agent);
    }

    /**
     * 启动恢复：回答活动工单占用的服务量。若坐席状态仍是 ONLINE，按 BUSY 恢复并补一条状态切换，
     * 避免重启后把正在办理的坐席当成可再次派单的空闲资源。
     */
    public synchronized void restoreServing(String agentId, int count) {
        Agent agent = agents.get(agentId);
        if (agent == null || count <= 0) return;
        long now = System.currentTimeMillis();
        agent.serving += count;
        agent.servingSince = now;
        if (!"BUSY".equals(agent.status)) {
            statusLog.change(agentId, agent.status, agent.statusSince, "BUSY", now, "RESTORE", "SYSTEM");
            agent.status = "BUSY";
            agent.statusSince = now;
            persist(agent);
        }
    }

    private void persist(Agent agent) {
        if (agentRepository != null) {
            agentRepository.save(agent);
        }
    }

    public synchronized Agent get(String agentId) { return agents.get(agentId); }

    public synchronized List<Agent> all() { return new ArrayList<Agent>(agents.values()); }

    /**
     * 技能组匹配：优先技能命中、其次无人服务、再次最早空闲，最后按服务量升序。
     * rejected 中的坐席本轮不参与匹配。
     */
    public synchronized Agent match(String skillGroup, Set<String> rejected) {
        Agent best = null;
        for (Agent agent : agents.values()) {
            if (!"ONLINE".equals(agent.status)) continue;
            if (rejected != null && rejected.contains(agent.agentId)) continue;
            if (skillGroup != null && !agent.has(skillGroup)) continue;
            if (best == null) { best = agent; continue; }
            if (agent.serving < best.serving) { best = agent; continue; }
            if (agent.serving == best.serving && agent.lastActive < best.lastActive) best = agent;
        }
        return best;
    }
}
