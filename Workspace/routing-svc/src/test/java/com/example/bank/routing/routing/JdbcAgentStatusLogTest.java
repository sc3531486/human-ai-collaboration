package com.example.bank.routing.routing;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdbcAgentStatusLogTest {

    private JdbcTemplate jdbc() {
        String url = "jdbc:h2:mem:routing-status-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        return new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    }

    @Test
    void closesPreviousIntervalAndOpensNewOne() {
        JdbcTemplate jdbc = jdbc();
        JdbcAgentStatusLog log = new JdbcAgentStatusLog(jdbc);
        log.initialize();

        log.change("a1", "OFFLINE", 1000L, "ONLINE", 5000L, null, "AGENT");
        log.change("a1", "ONLINE", 5000L, "BREAK", 8000L, "小休", "AGENT");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, duration_ms, ended_at FROM routing_agent_status_log ORDER BY id");
        assertEquals(3, rows.size());
        assertEquals("OFFLINE", rows.get(0).get("status"));
        assertEquals(4000L, ((Number) rows.get(0).get("duration_ms")).longValue());
        assertEquals("ONLINE", rows.get(1).get("status"));
        assertEquals(3000L, ((Number) rows.get(1).get("duration_ms")).longValue());
        assertEquals("BREAK", rows.get(2).get("status"));
        assertNull(rows.get(2).get("ended_at"));
    }

    @Test
    void firstChangeAfterRestartBackfillsPreviousInterval() {
        JdbcTemplate jdbc = jdbc();
        JdbcAgentStatusLog log = new JdbcAgentStatusLog(jdbc);
        log.initialize();

        // 没有未结束段（模拟重启后第一次切换）：补记 ONLINE 段，再开 BREAK 段。
        log.change("a1", "ONLINE", 1000L, "BREAK", 5000L, "小休", "AGENT");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, duration_ms, ended_at FROM routing_agent_status_log ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals("ONLINE", rows.get(0).get("status"));
        assertEquals(4000L, ((Number) rows.get(0).get("duration_ms")).longValue());
        assertEquals("BREAK", rows.get(1).get("status"));
        assertNull(rows.get(1).get("ended_at"));
    }
}
