package com.example.bank.signaling.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 连接生命周期：一次令牌签发对应一条连接记录，presence 掉线时关闭。 */
@Repository
public class SignalingConnectionRepository {

    private final JdbcTemplate jdbc;

    public SignalingConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 当前打开的连接；同一主体多数情况下只有一条。 */
    public List<Map<String, Object>> listOpen(String subjectId) {
        return jdbc.queryForList("SELECT connection_id FROM signaling_connection "
                + "WHERE subject_id=? AND state='OPEN' ORDER BY opened_at", subjectId);
    }

    public String open(String subjectId, String role, String channel, long at) {
        String connectionId = "CONN-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO signaling_connection(connection_id, subject_id, role, channel, state, opened_at) "
                        + "VALUES(?,?,?,?, 'OPEN', ?)",
                connectionId, subjectId, role, channel, new Timestamp(at));
        return connectionId;
    }

    public void touch(String connectionId, long at) {
        jdbc.update("UPDATE signaling_connection SET last_present_at=? WHERE connection_id=?",
                new Timestamp(at), connectionId);
    }

    public void close(String connectionId, long at, String reason) {
        jdbc.update("UPDATE signaling_connection SET state='CLOSED', closed_at=?, close_reason=? "
                + "WHERE connection_id=? AND state='OPEN'", new Timestamp(at), reason, connectionId);
    }
}
