package com.example.bank.business.port;

import java.util.List;
import java.util.Map;

/**
 * 出站端口：业务会话归属。
 *
 * <p>由流程引擎服务实现 —— 归属写在流程变量上，持有它的是流程引擎，
 * 本服务不缓存副本。</p>
 *
 * <p>与 {@link ProcessPort} 分开是因为关注点不同：那里是"业务推进到哪"，
 * 这里是"这单归谁"。两者的调用方与失败语义都不一样 ——
 * 归属校验失败是 409（业务已被他人接听），流程推进失败是 500。</p>
 */
public interface SessionPort {

    /** 某坐席可见的人工节点待办：未认领的对所有人可见，已认领的只对归属坐席可见。 */
    List<Map<String, Object>> queue(String agentId);

    /**
     * 坐席认领人工会话：把归属写进流程变量。
     *
     * <p>业务已被他人接听或已离开人工节点时抛 {@code RestClientResponseException}（409）。
     * 这两条判定必须在对端做 —— 归属就写在那张变量表上，先查再写会与抢单的坐席撞车。</p>
     */
    void claim(String processId, String agentId, String peerId);
    /** 接听组合动作失败时清理本坐席的归属，不终止业务实例。 */
    void releaseClaim(String processId, String agentId);

    /**
     * 切换通话模式：{@code av} / {@code audio} / {@code released}。
     *
     * <p>归在这里而不是另开一个端口：它与 {@link #claim} 共享同一个前置条件 ——
     * 只有归属坐席能改这单的通话状态，对端也把归属校验做在了同一次调用里。
     * 模式非法抛 400，归属不符抛 409。</p>
     */
    void setCallMode(String processId, String agentId, String mode);

    /**
     * 校验业务会话归属。
     *
     * <p>不属于该坐席时抛 {@code RestClientResponseException}（409），
     * 调用方原样转成对前端的冲突提示。</p>
     */
    void requireOwner(String processId, String agentId);

    /** 坐席结束业务：终止实例并释放归属。幂等，返回 {@code {closed, reason}}。 */
    Map<String, Object> close(String processId);
}
