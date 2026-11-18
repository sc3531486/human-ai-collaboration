package com.example.bank.routing.routing;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数字人注册表：维护数字人的技能组与占用状态，为队列路由提供"空闲数字人"匹配依据。
 * 结构与 {@link AgentRegistry} 对称，但<b>不做 presence 校准</b>——数字人是进程内资源、
 * 没有独立客户端连接，"空闲"即 {@code status=ONLINE} 且 {@code serving=0}。
 * 原型为进程内注册表；多实例部署时应替换为共享资源池。
 */
@Service
public class DigitalHumanRegistry {

    public static class DigitalHuman implements RoutingResource {
        public String digitalHumanId;
        public String name;
        /** 数字人音视频端标识，接入真实数字人服务后由该服务下发。 */
        public String peerId = "";
        public Set<String> skills = new LinkedHashSet<String>();
        /** ONLINE（空闲可接待）/ BUSY（服务中）/ OFFLINE（下线） */
        public String status = "OFFLINE";
        public int serving;
        public long lastActive = System.currentTimeMillis();

        public boolean has(String skill) { return skills.contains(skill); }

        public String id() { return digitalHumanId; }
        public Set<String> skills() { return skills; }
        public String status() { return status; }
        public int serving() { return serving; }
        public long lastActive() { return lastActive; }
    }

    private final Map<String, DigitalHuman> digitalHumans = new LinkedHashMap<String, DigitalHuman>();

    public synchronized void register(String digitalHumanId, String name, Collection<String> skills, String status) {
        if (digitalHumanId == null || digitalHumanId.trim().isEmpty()) throw new IllegalArgumentException("缺少数字人标识");
        if (!"ONLINE".equals(status) && !"OFFLINE".equals(status)) throw new IllegalArgumentException("无效数字人目录状态");
        DigitalHuman digitalHuman = digitalHumans.get(digitalHumanId);
        boolean created = digitalHuman == null;
        if (created) { digitalHuman = new DigitalHuman(); digitalHuman.digitalHumanId = digitalHumanId; }
        digitalHuman.name = name;
        digitalHuman.skills.clear();
        digitalHuman.skills.addAll(skills);
        if (created || digitalHuman.serving == 0) digitalHuman.status = status;
        digitalHumans.put(digitalHumanId, digitalHuman);
    }

    public synchronized void addServing(String digitalHumanId, int delta) {
        DigitalHuman digitalHuman = digitalHumans.get(digitalHumanId);
        if (digitalHuman == null) {
            // 资源池只增不删，取不到只能是不变量被破坏（如工单接待方与资源池失同步），如实抛出。
            throw new IllegalStateException("服务量变更指向不存在的数字人：" + digitalHumanId);
        }
        if (digitalHuman.serving + delta < 0) {
            // 占用不变量（占用与工单 ANSWERED 严格对应）被破坏时如实抛出，
            // 不再用 Math.max 把重复释放悄悄钳到 0 —— 那会让占用数与实际接待数静默对不上。
            throw new IllegalStateException("数字人服务量出现负数，疑似重复释放：digitalHumanId="
                    + digitalHumanId + "，delta=" + delta);
        }
        digitalHuman.serving = digitalHuman.serving + delta;
        digitalHuman.status = digitalHuman.serving > 0 ? "BUSY" : "ONLINE";
        digitalHuman.lastActive = System.currentTimeMillis();
    }

    public synchronized DigitalHuman get(String digitalHumanId) { return digitalHumans.get(digitalHumanId); }

    public synchronized List<DigitalHuman> all() { return new ArrayList<DigitalHuman>(digitalHumans.values()); }

    /**
     * 空闲数字人匹配：优先技能命中、其次服务量少、最后最早空闲。
     * excluded 中的数字人本轮不参与匹配；与 {@link AgentRegistry#match(String, Set)} 判定语义一致。
     */
    public synchronized DigitalHuman match(String skillGroup, Set<String> excluded) {
        DigitalHuman best = null;
        for (DigitalHuman digitalHuman : digitalHumans.values()) {
            if (!"ONLINE".equals(digitalHuman.status)) continue;
            if (excluded != null && excluded.contains(digitalHuman.digitalHumanId)) continue;
            if (skillGroup != null && !digitalHuman.has(skillGroup)) continue;
            if (best == null) { best = digitalHuman; continue; }
            if (digitalHuman.serving < best.serving) { best = digitalHuman; continue; }
            if (digitalHuman.serving == best.serving && digitalHuman.lastActive < best.lastActive) best = digitalHuman;
        }
        return best;
    }
}
