package com.example.bank.routing.routing;

import com.example.bank.routing.persistence.JdbcTimes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 坐席状态变更记录的 JDBC 实现。
 *
 * <p>每次状态切换：关闭当前未结束状态段（写 ended_at / duration_ms），再插入新状态段。
 * 服务重启后第一次切换时若没有未结束段，则用切换前的状态和起始时间补一条已结束段，
 * 避免重启丢掉上一段时长。</p>
 */
@Service
public class JdbcAgentStatusLog implements AgentStatusLog {

    private final JdbcTemplate jdbc;

    public JdbcAgentStatusLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS routing_agent_status_log ("
                + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '记录ID',"
                + "agent_id VARCHAR(64) NOT NULL COMMENT '坐席/接待资源ID',"
                + "status VARCHAR(16) NOT NULL COMMENT '本段状态：ONLINE/BUSY/BREAK/OFFLINE',"
                + "started_at DATETIME NOT NULL COMMENT '本段开始时间',"
                + "ended_at DATETIME DEFAULT NULL COMMENT '本段结束时间，NULL 表示当前仍处于该状态',"
                + "duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '本段时长（毫秒），结束时回填',"
                + "reason VARCHAR(255) DEFAULT NULL COMMENT '状态原因，如小休原因',"
                + "source VARCHAR(32) NOT NULL DEFAULT 'SYSTEM' COMMENT '来源：AGENT/SYSTEM',"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                + "PRIMARY KEY (id),"
                + "KEY idx_status_agent (agent_id, started_at),"
                + "KEY idx_status_time (status, started_at)"
                + ")");
    }

    @Override
    @Transactional
    public synchronized void change(String agentId, String fromStatus, long fromStartedAt,
                                    String toStatus, long at, String reason, String source) {
        if (agentId == null || agentId.trim().isEmpty()) {
            throw new IllegalArgumentException("状态变更缺少 agentId");
        }
        if (toStatus == null || toStatus.trim().isEmpty()) {
            throw new IllegalArgumentException("状态变更缺少目标状态");
        }
        if (fromStatus != null && fromStatus.equals(toStatus)) {
            return;
        }

        List<Map<String, Object>> open = jdbc.queryForList(
                "SELECT id, started_at FROM routing_agent_status_log "
                        + "WHERE agent_id=? AND ended_at IS NULL ORDER BY started_at DESC LIMIT 1", agentId);
        if (!open.isEmpty()) {
            Map<String, Object> row = open.get(0);
            long id = ((Number) row.get("id")).longValue();
            long startedAt = JdbcTimes.millis(row.get("started_at"));
            close(id, startedAt, at);
        } else if (fromStatus != null && fromStartedAt > 0 && at > fromStartedAt) {
            // 重启后第一次切换：补记上一段，避免丢掉 fromStartedAt → at 的时长。
            insert(agentId, fromStatus, fromStartedAt, at, at - fromStartedAt, reason, source, true);
        }
        insert(agentId, toStatus, at, 0L, 0L, reason, source, false);
    }

    private void close(long id, long startedAt, long at) {
        long duration = Math.max(0L, at - startedAt);
        jdbc.update("UPDATE routing_agent_status_log SET ended_at=?, duration_ms=? WHERE id=?",
                new Timestamp(at), duration, id);
    }

    private void insert(String agentId, String status, long startedAt, long endedAt,
                        long duration, String reason, String source, boolean closed) {
        jdbc.update("INSERT INTO routing_agent_status_log"
                        + "(agent_id,status,started_at,ended_at,duration_ms,reason,source) VALUES(?,?,?,?,?,?,?)",
                agentId, status, new Timestamp(startedAt),
                closed ? new Timestamp(endedAt) : null, duration, reason, source);
    }
}
