package com.example.bank.signaling.presence;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已签发连接令牌的主体名册：presence 扫描据此知道该盯哪些频道。
 *
 * <p><b>为什么需要这份名册</b>：坐席名册的权威副本在路由服务（它是路由资源），信令服务不该反向依赖它。
 * 信令只关心"我签发过令牌的主体"，这是它自己产生的、不依赖任何其他服务的事实。</p>
 *
 * <p><b>名册是内存态，重启即失</b>：与路由服务的资源在线态同款性质。重启后需各端重新换取令牌才会
 * 重新纳入扫描 —— 这不会漏判掉线，只会漏掉"重启前就已断线"的历史，而那本就是重启后无法追溯的。</p>
 *
 * <p><b>入参非法一律抛异常，不做静默忽略</b>：空标识、未知角色、标记未登记的主体，
 * 都是调用方的编程错误 —— 静默返回只会把缺陷藏进扫描结果里（例如给未登记主体"成功"标记），
 * 因此全部显式抛 {@link IllegalArgumentException}。</p>
 *
 * <p><b>每个主体记两个状态位</b>：
 * {@code reported} 防止同一个断线事实每个扫描周期重复上报（刷日志、刷 HTTP 调用）；
 * 而一旦 presence 恢复为正数，{@code reported} 会被清除 —— 因为 Centrifugo 客户端会用<b>同一个令牌</b>
 * 自动重连，重连不会经过本服务换新令牌，不靠这条路径复位就会永久失联。</p>
 */
@Component
public class ConnectedSubjectRegistry {

    public static final String ROLE_AGENT = "AGENT";
    public static final String ROLE_CUSTOMER = "CUSTOMER";

    private final Map<String, Subject> subjects = new ConcurrentHashMap<String, Subject>();

    /** 一个已签发令牌的主体。字段可被扫描线程与请求线程并发读写，故用 volatile。 */
    public static class Subject {
        public final String id;
        /** 主体角色：每次令牌签发按最新一次刷新（见 {@link #register(String, String)}）。 */
        public volatile String role;
        /** 令牌签发时间，用于签入宽限期判定 */
        public volatile long registeredAt;
        /** 本轮断线事实是否已上报过 */
        public volatile boolean reported;

        Subject(String id, String role, long registeredAt) {
            this.id = id;
            this.role = role;
            this.registeredAt = registeredAt;
        }
    }

    /**
     * 登记一次令牌签发。同一主体重复换取令牌视为<b>新一次连接</b>：
     * 刷新宽限期起点、按最新一次签发刷新角色并清除已上报标记，避免刚重连就被判离线。
     *
     * @throws IllegalArgumentException 标识为空或角色不在 {@code AGENT/CUSTOMER} 取值域内
     */
    public void register(String id, String role) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("主体标识不能为空");
        }
        if (!ROLE_AGENT.equals(role) && !ROLE_CUSTOMER.equals(role)) {
            throw new IllegalArgumentException("未知主体角色: " + role);
        }
        subjects.compute(id, (key, existing) -> {
            Subject subject = existing == null ? new Subject(key, role, 0L) : existing;
            subject.role = role;
            subject.registeredAt = System.currentTimeMillis();
            subject.reported = false;
            return subject;
        });
    }

    /**
     * 标记该主体的断线事实已上报。
     *
     * @throws IllegalArgumentException 主体未登记 —— 扫描只处理名册里的主体，
     *                                  走到这里说明调用方顺序有错，静默忽略会掩盖它
     */
    public void markReported(String id) {
        subjectForMark(id).reported = true;
    }

    /**
     * 启动恢复：从数据库恢复纳管主体与已上报状态，避免重启后漏掉扫描对象。
     *
     * @throws IllegalArgumentException 标识为空、角色非法 —— 持久化数据损坏时启动即失败
     */
    public void restore(String id, String role, long registeredAt, boolean reported) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("恢复主体标识不能为空");
        }
        if (!ROLE_AGENT.equals(role) && !ROLE_CUSTOMER.equals(role)) {
            throw new IllegalArgumentException("恢复主体角色非法: " + role);
        }
        subjects.compute(id, (key, existing) -> {
            Subject subject = existing == null ? new Subject(key, role, registeredAt) : existing;
            subject.role = role;
            subject.registeredAt = registeredAt;
            subject.reported = reported;
            return subject;
        });
    }

    /**
     * 标记该主体的频道内仍有连接：清除已上报标记，使其下次真的断线时能再次上报。
     *
     * @return 本次是否清除了“已上报”标记（用于上层决定是否写恢复事件）
     * @throws IllegalArgumentException 主体未登记，理由同 {@link #markReported(String)}
     */
    public boolean markPresent(String id) {
        Subject subject = subjectForMark(id);
        boolean wasReported = subject.reported;
        subject.reported = false;
        return wasReported;
    }

    private Subject subjectForMark(String id) {
        Subject subject = subjects.get(id);
        if (subject == null) {
            throw new IllegalArgumentException("主体未登记，无法标记状态: " + id);
        }
        return subject;
    }

    /** 待扫描的坐席主体。客户连接消失当前没有业务含义，故不参与判定。 */
    public Collection<Subject> agents() {
        List<Subject> rows = new ArrayList<Subject>();
        for (Subject subject : subjects.values()) {
            if (ROLE_AGENT.equals(subject.role)) rows.add(subject);
        }
        return rows;
    }

    /** 观测用：当前纳管的主体总数。 */
    public int size() { return subjects.size(); }
}
