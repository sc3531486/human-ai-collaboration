package com.example.bank.business.port;

import com.example.bank.business.contract.Agent;
import com.example.bank.business.contract.DigitalHuman;

import java.util.List;
import java.util.Map;

/**
 * 出站端口：坐席与数字人名册。
 *
 * <p>由排队路由服务实现。名册的唯一副本在路由服务 —— 本服务只读、只转发，
 * <b>不缓存、不回写</b>。缓存一份就会出现两份状态互相打脸：路由那边工单已经回队，
 * 这里还显示坐席在忙。</p>
 *
 * <p>与 {@link RoutingPort} 分开：那里是"这单给谁了"，这里是"谁能接"。
 * 两者的失败语义不同 —— 查不到工单是正常路径，查不到坐席却是异常。</p>
 */
public interface AgentPort {

    /**
     * 坐席签入 / 状态变更。
     *
     * <p>返回路由侧的状态回执（振铃中的工单、该坐席可见的队列、队列总长）——
     * 签入是页面刷新后恢复现场的时刻，这三项必须与名册同源，
     * 由本服务自己拼装就会与路由的实际状态脱节。</p>
     *
     * @param skills 逗号分隔的技能组；空串表示不改动既有技能组
     */
    Map<String, Object> setStatus(String agentId, String name, String peerId, String skills, String status);

    /** 心跳活性登记。只更新时间戳，不改变状态。坐席不存在时静默无效。 */
    void touch(String agentId);

    /**
     * 坐席掉线。
     *
     * <p>路由侧实现是幂等的：名下的工单第一次处理后不再指向该坐席，第二次自然跳过。
     * 这是"信令 presence 归零"与"本地心跳停滞"两条判据能先后触发而不重复扣减服务量的前提。</p>
     */
    void offline(String agentId);

    /** 某坐席的当前负载摘要：{@code {agentId, ringing, queue, queueSize}}。 */
    Map<String, Object> summary(String agentId);

    /** 全量坐席名册，含当日计时与计数。 */
    List<Agent> all();

    /** 数字人名册与占用状态。 */
    List<DigitalHuman> digitalHumans();

    /** 某坐席的当日统计：{@code {signInSeconds, breakSeconds, serviceSeconds,
     * averageServiceSeconds, servedCount, successCount}}。
     *
     * <p>换算在对端做，本服务只转发。计时字段与状态机的写法一一对应，
     * 照着一份镜像字段在本服务复刻规则，规则一改就会分叉 —— 而分叉只表现为时长数字对不上，不报错。</p>
     */
    Map<String, Object> statsToday(String agentId);

    /** 登记坐席交易成功。 */
    void recordSuccess(String agentId);
}
