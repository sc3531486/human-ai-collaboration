package com.example.bank.routing.routing;

import com.example.bank.routing.persistence.RoutingSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentStatsServiceTest {

    @Test
    void readsDurationsFromStatusIntervalsAndCountsFromTicketEvents() {
        String url = "jdbc:h2:mem:agent-stats-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        new JdbcAgentStatusLog(jdbc).initialize();
        new RoutingSchemaInitializer(jdbc).initialize();

        long dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long t1 = dayStart + 3600_000L;      // 01:00
        long t2 = t1 + 3600_000L;           // 02:00，ONLINE 1 小时
        long t3 = t2 + 900_000L;            // 02:15，BREAK 15 分钟
        long t4 = t3 + 600_000L;            // 02:25，ONLINE 10 分钟

        insertStatus(jdbc, "a1", "ONLINE", t1, t2, t2 - t1);
        insertStatus(jdbc, "a1", "BREAK", t2, t3, t3 - t2);
        insertStatus(jdbc, "a1", "ONLINE", t3, t4, t4 - t3);

        jdbc.update("INSERT INTO routing_ticket(ticket_id,biz_key,queue_id,skill_group,status,handler_type,agent_id,answered_at,completed_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)",
                "T1", "B1", "default", "GENERAL", "COMPLETED", "HUMAN", "a1",
                new Timestamp(t1), new Timestamp(t1 + 60_000L));
        insertEvent(jdbc, "T1", "TICKET_ANSWERED", "a1", t1);
        insertEvent(jdbc, "T1", "TICKET_COMPLETED", "a1", t1 + 60_000L);

        Map<String, Object> stats = new AgentStatsService(jdbc).today("a1");

        assertEquals(5100L, stats.get("signInSeconds"));   // 1h + 15m + 10m = 1h25m
        assertEquals(900L, stats.get("breakSeconds"));     // 15m
        assertEquals(60L, stats.get("serviceSeconds"));    // 60s
        assertEquals(1, stats.get("servedCount"));
        assertEquals(1, stats.get("successCount"));
    }

    private void insertStatus(JdbcTemplate jdbc, String agentId, String status,
                              long startedAt, long endedAt, long duration) {
        jdbc.update("INSERT INTO routing_agent_status_log(agent_id,status,started_at,ended_at,duration_ms,source) "
                        + "VALUES(?,?,?,?,?,?)",
                agentId, status, new Timestamp(startedAt), new Timestamp(endedAt), duration, "AGENT");
    }

    private void insertEvent(JdbcTemplate jdbc, String ticketId, String type, String agentId, long at) {
        jdbc.update("INSERT INTO routing_ticket_event(ticket_id,event_type,agent_id,occurred_at) VALUES(?,?,?,?)",
                ticketId, type, agentId, new Timestamp(at));
    }
}
