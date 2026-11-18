package com.example.bank.signaling.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 信令产品表初始化：启动幂等建表，单测用 H2 MySQL 模式可直接跑。
 *
 * <p>只建本服务拥有的表；连接在线态仍以 Centrifugo presence 为准，这里持久化的是
 * “签发过令牌的主体 / 连接生命周期 / 事件流水”这三类事实。</p>
 */
@Component
public class SignalingSchemaInitializer {

    private final JdbcTemplate jdbc;

    public SignalingSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS signaling_subject ("
                + "subject_id VARCHAR(100) NOT NULL,"
                + "role VARCHAR(16) NOT NULL,"
                + "channel VARCHAR(160) NOT NULL,"
                + "reported TINYINT NOT NULL DEFAULT 0,"
                + "registered_at DATETIME NOT NULL,"
                + "last_present_at DATETIME DEFAULT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (subject_id)"
                + ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS signaling_connection ("
                + "connection_id VARCHAR(64) NOT NULL,"
                + "subject_id VARCHAR(100) NOT NULL,"
                + "role VARCHAR(16) NOT NULL,"
                + "channel VARCHAR(160) NOT NULL,"
                + "state VARCHAR(16) NOT NULL,"
                + "opened_at DATETIME NOT NULL,"
                + "closed_at DATETIME DEFAULT NULL,"
                + "close_reason VARCHAR(64) DEFAULT NULL,"
                + "last_present_at DATETIME DEFAULT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (connection_id),"
                + "KEY idx_conn_subject (subject_id, opened_at),"
                + "KEY idx_conn_state (subject_id, state)"
                + ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS signaling_event ("
                + "event_id VARCHAR(64) NOT NULL,"
                + "subject_id VARCHAR(100) NOT NULL,"
                + "connection_id VARCHAR(64) DEFAULT NULL,"
                + "event_type VARCHAR(32) NOT NULL,"
                + "occurred_at DATETIME NOT NULL,"
                + "payload_json MEDIUMTEXT DEFAULT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (event_id),"
                + "KEY idx_event_subject (subject_id, occurred_at),"
                + "KEY idx_event_type (event_type, occurred_at)"
                + ")");
    }
}
