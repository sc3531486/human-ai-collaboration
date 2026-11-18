package com.example.bank.routing.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 坐席日统计仓储：绩效报表的汇总读模型。
 *
 * <p>明细事实仍来自 {@code routing_agent_status_log} 与 {@code routing_ticket_event}；
 * 这里只做当日增量，避免报表每次扫明细表。</p>
 */
@Service
public class RoutingDailyStatsRepository {

    private final JdbcTemplate jdbc;

    public RoutingDailyStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordAnswered(String agentId, long ringMs) {
        ensureRow(agentId);
        jdbc.update("UPDATE routing_agent_daily_stats SET "
                        + "avg_ring_ms = CASE WHEN answered_count=0 THEN ? "
                        + "ELSE (avg_ring_ms*answered_count + ?)/(answered_count+1) END, "
                        + "answered_count=answered_count+1, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE stat_date=CURRENT_DATE AND agent_id=?",
                Math.max(0L, ringMs), Math.max(0L, ringMs), agentId);
    }

    public void recordRejected(String agentId) {
        ensureRow(agentId);
        jdbc.update("UPDATE routing_agent_daily_stats SET rejected_count=rejected_count+1, "
                + "updated_at=CURRENT_TIMESTAMP WHERE stat_date=CURRENT_DATE AND agent_id=?", agentId);
    }

    public void recordServed(String agentId, long serviceMs) {
        ensureRow(agentId);
        jdbc.update("UPDATE routing_agent_daily_stats SET "
                        + "served_count=served_count+1, service_ms=service_ms+?, "
                        + "avg_service_ms = CASE WHEN served_count=0 THEN ? "
                        + "ELSE (avg_service_ms*served_count + ?)/(served_count+1) END, "
                        + "updated_at=CURRENT_TIMESTAMP "
                        + "WHERE stat_date=CURRENT_DATE AND agent_id=?",
                Math.max(0L, serviceMs), Math.max(0L, serviceMs), Math.max(0L, serviceMs), agentId);
    }

    public void recordSuccess(String agentId) {
        ensureRow(agentId);
        jdbc.update("UPDATE routing_agent_daily_stats SET success_count=success_count+1, "
                + "updated_at=CURRENT_TIMESTAMP WHERE stat_date=CURRENT_DATE AND agent_id=?", agentId);
    }

    public void addDurations(String agentId, long onlineMs, long busyMs, long breakMs) {
        ensureRow(agentId);
        jdbc.update("UPDATE routing_agent_daily_stats SET online_ms=online_ms+?, busy_ms=busy_ms+?, "
                        + "break_ms=break_ms+?, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE stat_date=CURRENT_DATE AND agent_id=?",
                Math.max(0L, onlineMs), Math.max(0L, busyMs), Math.max(0L, breakMs), agentId);
    }

    public List<Map<String, Object>> today(String agentId) {
        return jdbc.queryForList("SELECT * FROM routing_agent_daily_stats "
                + "WHERE stat_date=CURRENT_DATE AND agent_id=?", agentId);
    }

    private void ensureRow(String agentId) {
        jdbc.update("INSERT IGNORE INTO routing_agent_daily_stats(stat_date, agent_id) "
                + "VALUES(CURRENT_DATE, ?)", agentId);
    }
}
