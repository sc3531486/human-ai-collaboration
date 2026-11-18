package com.example.bank.business.contract;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 坐席名册条目的跨服务镜像。
 *
 * <p><b>字段名与默认值必须与 routing-svc 的 {@code AgentRegistry.Agent} 逐字一致</b>，
 * 理由见 {@link QueueTicket}。</p>
 *
 * <p>后段的计时与计数（{@code onlineMillis} / {@code servedCount} 等）是路由服务的
 * 进程内累计值。本服务只做展示换算，<b>不缓存、不回写</b> —— 名册的唯一副本在路由服务。</p>
 */
public class Agent {
    public String agentId;
    public String name;
    public String peerId = "";
    public Set<String> skills = new LinkedHashSet<String>();
    public String status = "OFFLINE";
    public int serving;
    public long lastActive = System.currentTimeMillis();

    /** 当前状态的开始时间戳。 */
    public long statusSince = System.currentTimeMillis();
    /** 累计签入时长（ONLINE + BUSY，毫秒）。 */
    public long onlineMillis;
    /** 累计小休时长（BREAK，毫秒）。 */
    public long breakMillis;
    /** 累计办理时长（毫秒）。 */
    public long serviceMillis;
    /** 当前这段办理的开始时间戳。 */
    public long servingSince;
    /** 当日已服务次数。 */
    public int servedCount;
    /** 当日交易成功数。 */
    public int successCount;
}
