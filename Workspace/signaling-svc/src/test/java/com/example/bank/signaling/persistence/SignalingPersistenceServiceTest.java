package com.example.bank.signaling.persistence;

import com.example.bank.signaling.presence.ConnectedSubjectRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalingPersistenceServiceTest {

    private JdbcTemplate jdbc() {
        String url = "jdbc:h2:mem:signaling-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        new SignalingSchemaInitializer(jdbc).initialize();
        return jdbc;
    }

    private SignalingPersistenceService service(JdbcTemplate jdbc) {
        return new SignalingPersistenceService(
                new SignalingSubjectRepository(jdbc),
                new SignalingConnectionRepository(jdbc),
                new SignalingEventRepository(jdbc),
                new ObjectMapper(), 60_000L);
    }

    @Test
    void tokenIssuePersistsSubjectConnectionAndTokenEvent() {
        JdbcTemplate jdbc = jdbc();
        long at = System.currentTimeMillis();
        service(jdbc).tokenIssued("AGENT-1", "AGENT", "agent:AGENT-1", at);

        Map<String, Object> subject = jdbc.queryForMap(
                "SELECT role, channel, reported FROM signaling_subject WHERE subject_id=?", "AGENT-1");
        assertEquals("AGENT", subject.get("role"));
        assertEquals("agent:AGENT-1", subject.get("channel"));
        assertEquals(0, ((Number) subject.get("reported")).intValue());

        Map<String, Object> connection = jdbc.queryForMap(
                "SELECT state FROM signaling_connection WHERE subject_id=?", "AGENT-1");
        assertEquals("OPEN", connection.get("state"));

        assertEquals(1, count(jdbc, "TOKEN_ISSUED"));
        assertEquals(1, count(jdbc, "CONNECTION_OPENED"));
    }

    @Test
    void presenceOnlineThenOfflineClosesConnectionAndWritesEvents() {
        JdbcTemplate jdbc = jdbc();
        SignalingPersistenceService service = service(jdbc);
        long t1 = System.currentTimeMillis();
        service.tokenIssued("AGENT-1", "AGENT", "agent:AGENT-1", t1);
        service.presenceOnline("AGENT-1", "AGENT", "agent:AGENT-1", t1 + 1000, false);
        service.presenceOffline("AGENT-1", "AGENT", "agent:AGENT-1", t1 + 2000, "PRESENCE_EMPTY");

        Map<String, Object> connection = jdbc.queryForMap(
                "SELECT state, close_reason FROM signaling_connection WHERE subject_id=?", "AGENT-1");
        assertEquals("CLOSED", connection.get("state"));
        assertEquals("PRESENCE_EMPTY", connection.get("close_reason"));
        assertNotNull(jdbc.queryForMap("SELECT last_present_at FROM signaling_subject WHERE subject_id=?", "AGENT-1")
                .get("last_present_at"));

        assertEquals(1, count(jdbc, "CONNECTION_CLOSED"));
        assertEquals(2, count(jdbc, "PRESENCE_CHANGED")); // online + offline

        // 重复掉线不重复写事件（没有打开的连接）
        service.presenceOffline("AGENT-1", "AGENT", "agent:AGENT-1", t1 + 3000, "PRESENCE_EMPTY");
        assertEquals(1, count(jdbc, "CONNECTION_CLOSED"));
        assertEquals(2, count(jdbc, "PRESENCE_CHANGED"));
    }

    @Test
    void reportedStatePersistsAndRestoresIntoRegistry() {
        JdbcTemplate jdbc = jdbc();
        SignalingPersistenceService service = service(jdbc);
        long at = System.currentTimeMillis();
        service.tokenIssued("AGENT-1", "AGENT", "agent:AGENT-1", at);
        service.reported("AGENT-1", true);

        ConnectedSubjectRegistry registry = new ConnectedSubjectRegistry();
        new SignalingSubjectBootstrap(new SignalingSubjectRepository(jdbc), registry).restore();

        assertEquals(1, registry.size());
        assertEquals(1, registry.agents().size());
        assertTrue(registry.agents().iterator().next().reported);
    }

    private int count(JdbcTemplate jdbc, String eventType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signaling_event WHERE event_type=?", Integer.class, eventType);
        return count == null ? 0 : count;
    }
}
