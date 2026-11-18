package com.example.bank.media.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/** 视频用户登记（media_user）。 */
@Repository
public class MediaUserRepository {

    private final JdbcTemplate jdbc;

    public MediaUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS media_user ("
                + "user_id VARCHAR(64) NOT NULL,"
                + "peer_id VARCHAR(64) NOT NULL,"
                + "subject_id VARCHAR(100) NOT NULL,"
                + "subject_name VARCHAR(100) DEFAULT NULL,"
                + "subject_role VARCHAR(32) NOT NULL,"
                + "platform_code VARCHAR(32) NOT NULL,"
                + "platform_user_id VARCHAR(128) DEFAULT NULL,"
                + "state VARCHAR(16) NOT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "last_active_at DATETIME DEFAULT NULL,"
                + "released_at DATETIME DEFAULT NULL,"
                + "PRIMARY KEY (user_id),"
                + "UNIQUE (peer_id),"
                + "KEY idx_user_subject (subject_id)"
                + ")");
    }

    public void insert(String userId, String peerId, String subjectId, String subjectName,
                       String subjectRole, String platformCode, String platformUserId) {
        jdbc.update("INSERT INTO media_user"
                        + "(user_id,peer_id,subject_id,subject_name,subject_role,platform_code,"
                        + "platform_user_id,state,last_active_at) VALUES(?,?,?,?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP)",
                userId, peerId, subjectId, subjectName, subjectRole, platformCode, platformUserId);
    }

    public Map<String, Object> findByPeerId(String peerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM media_user WHERE peer_id=?", peerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findActiveBySubject(String subjectId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM media_user WHERE subject_id=? AND state='ACTIVE' "
                        + "ORDER BY created_at DESC LIMIT 1", subjectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void touch(String userId) {
        jdbc.update("UPDATE media_user SET last_active_at=CURRENT_TIMESTAMP WHERE user_id=?", userId);
    }

    public void release(String userId) {
        jdbc.update("UPDATE media_user SET state='RELEASED', released_at=CURRENT_TIMESTAMP "
                + "WHERE user_id=? AND state<>'RELEASED'", userId);
    }
}
