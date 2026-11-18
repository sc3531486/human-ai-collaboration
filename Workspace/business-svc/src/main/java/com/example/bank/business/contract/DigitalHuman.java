package com.example.bank.business.contract;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 数字人名册条目的跨服务镜像。
 *
 * <p><b>字段名与默认值必须与 routing-svc 的 {@code DigitalHumanRegistry.DigitalHuman} 逐字一致</b>，
 * 理由见 {@link QueueTicket}。注意 {@code status} 的默认值是对端的 {@code ONLINE}，
 * 不是 {@link Agent} 的 {@code OFFLINE} —— 两个名册的空闲语义不同。</p>
 */
public class DigitalHuman {
    public String digitalHumanId;
    public String name;
    public String peerId = "";
    public Set<String> skills = new LinkedHashSet<String>();
    /** ONLINE（空闲可接待）/ BUSY（服务中）/ OFFLINE（下线） */
    public String status = "ONLINE";
    public int serving;
    public long lastActive = System.currentTimeMillis();
}
