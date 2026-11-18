package com.example.bank.routing.routing;

/**
 * 坐席状态变更记录端口。
 *
 * <p>状态按区间登记：关闭上一段、开启下一段。绩效查询直接读
 * {@code routing_agent_status_log}，不靠进程内计时。</p>
 */
public interface AgentStatusLog {

    /**
     * 记录一次状态切换。
     *
     * @param agentId       坐席/接待资源ID
     * @param fromStatus    切换前状态；首次登记时可为空
     * @param fromStartedAt 切换前状态的开始时间（毫秒）
     * @param toStatus      切换后状态
     * @param at            切换时间（毫秒）
     * @param reason        原因（可空）
     * @param source        来源（AGENT/SYSTEM）
     */
    void change(String agentId, String fromStatus, long fromStartedAt,
                String toStatus, long at, String reason, String source);

    /** 单元测试/无库环境的空实现。 */
    AgentStatusLog NOOP = new AgentStatusLog() {
        @Override
        public void change(String agentId, String fromStatus, long fromStartedAt,
                           String toStatus, long at, String reason, String source) {
            // 无持久化时不影响路由主流程
        }
    };
}
