package com.example.bank.signaling.port;

/**
 * 出站端口：把"观察到某坐席的连接消失"这一事实抛给业务逻辑服务。
 *
 * <p>端口由信令服务定义、由业务逻辑服务实现 —— 方向是反转的。信令只声明
 * "我观察到这条连接没了"，至于这个事实意味着坐席小休、办理中还是下班，
 * 以及要不要据此把坐席置为离线，都是业务逻辑服务的判断。</p>
 *
 * <p>这是整个拆分里<b>唯一一条指回接口面的边</b>，它不破坏分层：
 * 上报的是连接层的事实，不是业务指令。</p>
 */
public interface PresenceEventPort {

    /**
     * 上报一次坐席连接消失。
     *
     * <p>按规格（CONTRACTS.md 第三节）：上报失败只记日志、不重试、不抛出 ——
     * 但失败必须<b>如实返回 {@code false}</b>，绝不允许调用方在投递失败时仍把该事实
     * 标记为"已上报"，否则规格所述"下一个扫描周期会重新观察并再次上报"的恢复路径
     * 就会被这一标记堵死，掉线事实将随一次 HTTP 抖动永久丢失。</p>
     *
     * @param agentId 坐席标识
     * @param reason  观察到的现象：{@code PRESENCE_EMPTY}（连接层消失）
     * @param source  生产者身份：{@code PRESENCE_SWEEP}（当前）/{@code SOCKET_CALLBACK}（通道升级后）
     * @param channel 发生消失的频道，便于业务侧核对
     * @return {@code true} 表示业务逻辑服务已接收该事件；{@code false} 表示投递失败
     *         （已按规格记 ERROR 日志），调用方不得视为已上报
     */
    boolean disconnected(String agentId, String reason, String source, String channel);
}
