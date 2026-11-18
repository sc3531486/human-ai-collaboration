package com.example.bank.routing.routing;

import com.example.bank.routing.persistence.JdbcTimes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 坐席当日统计：绩效查询直接读 {@code routing_agent_status_log}（状态区间表）和
 * {@code routing_ticket}/{@code routing_ticket_event}，不再依赖进程内累计。
 *
 * <p>状态表一行是一个状态区间（ended_at 为空表示当前仍在进行），因此在线/小休/忙碌
 * 时长可以在 SQL 侧按时间段裁剪后直接求和；服务时长与接听/办结量从工单事实取数。
 * 这样服务重启、换实例都不会丢绩效数据。</p>
 */
@Service
public class AgentStatsService {

    private final JdbcTemplate jdbc;

    public AgentStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 坐席当日统计快照。坐席不存在或当日无记录时返回全 0，保证前端始终能渲染。
     * 秒数包含"当前仍在进行中"的状态段。
     */
    public Map<String, Object> today(String agentId) {
        long now = System.currentTimeMillis();
        long dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        long signInMillis = 0L;
        long breakMillis = 0L;
        long serviceMillis = 0L;

        // 1) 状态区间：与今天有交集的行，按 [dayStart, now] 裁剪后累加
        List<Map<String, Object>> statusRows = jdbc.queryForList(
                "SELECT status, started_at, ended_at FROM routing_agent_status_log "
                        + "WHERE agent_id=? AND started_at < ? AND (ended_at IS NULL OR ended_at > ?)",
                agentId, new Timestamp(now), new Timestamp(dayStart));
        for (Map<String, Object> row : statusRows) {
            long startedAt = JdbcTimes.millis(row.get("started_at"));
            Object endedAt = row.get("ended_at");
            long end = endedAt == null ? now : JdbcTimes.millis(endedAt);
            long from = Math.max(startedAt, dayStart);
            long to = Math.min(end, now);
            long millis = Math.max(0L, to - from);
            if ("ONLINE".equals(row.get("status")) || "BUSY".equals(row.get("status"))
                    || "BREAK".equals(row.get("status"))) {
                signInMillis += millis;
            }
            if ("BREAK".equals(row.get("status"))) {
                breakMillis += millis;
            }
        }

        // 2) 服务时长：工单 answered_at→completed_at（在途按 now 截断）
        List<Map<String, Object>> tickets = jdbc.queryForList(
                "SELECT answered_at, completed_at FROM routing_ticket "
                        + "WHERE agent_id=? AND answered_at IS NOT NULL AND answered_at < ? "
                        + "AND (completed_at IS NULL OR completed_at > ?)",
                agentId, new Timestamp(now), new Timestamp(dayStart));
        for (Map<String, Object> row : tickets) {
            long answeredAt = JdbcTimes.millis(row.get("answered_at"));
            Object completedAt = row.get("completed_at");
            long end = completedAt == null ? now : JdbcTimes.millis(completedAt);
            long from = Math.max(answeredAt, dayStart);
            long to = Math.min(end, now);
            serviceMillis += Math.max(0L, to - from);
        }

        int servedCount = countEvents(agentId, "TICKET_ANSWERED", dayStart);
        int successCount = countEvents(agentId, "TICKET_COMPLETED", dayStart);

        long signInSeconds = signInMillis / 1000L;
        long breakSeconds = breakMillis / 1000L;
        long serviceSeconds = serviceMillis / 1000L;
        long averageServiceSeconds = servedCount > 0 ? serviceSeconds / servedCount : 0L;

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("signInSeconds", signInSeconds);
        data.put("breakSeconds", breakSeconds);
        data.put("serviceSeconds", serviceSeconds);
        data.put("averageServiceSeconds", averageServiceSeconds);
        data.put("servedCount", servedCount);
        data.put("successCount", successCount);
        return data;
    }

    private int countEvents(String agentId, String eventType, long since) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM routing_ticket_event WHERE agent_id=? AND event_type=? AND occurred_at>=?",
                Integer.class, agentId, eventType, new Timestamp(since));
        return count == null ? 0 : count;
    }
}
