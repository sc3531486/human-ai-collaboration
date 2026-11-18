package com.example.bank.routing.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 路由产品表初始化：启动时幂等建表，单测用 H2 MySQL 模式可直接跑。
 *
 * <p>只建本服务拥有的表；{@code routing_agent_status_log} 由
 * {@code JdbcAgentStatusLog} 负责，避免两处 DDL 分叉。</p>
 */
@Component
public class RoutingSchemaInitializer {

    private final JdbcTemplate jdbc;

    public RoutingSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS routing_queue ("
                + "queue_id VARCHAR(64) NOT NULL,"
                + "queue_name VARCHAR(100) NOT NULL,"
                + "skill_group VARCHAR(64) NOT NULL,"
                + "priority INT NOT NULL DEFAULT 0,"
                + "strategy VARCHAR(32) NOT NULL DEFAULT 'LONGEST_IDLE',"
                + "max_wait_ms BIGINT NOT NULL DEFAULT 0,"
                + "ring_timeout_ms BIGINT NOT NULL DEFAULT 45000,"
                + "overflow_queue_id VARCHAR(64) DEFAULT NULL,"
                + "enabled TINYINT NOT NULL DEFAULT 1,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (queue_id),"
                + "KEY idx_queue_skill (skill_group)"
                + ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS routing_agent ("
                + "agent_id VARCHAR(64) NOT NULL,"
                + "agent_name VARCHAR(100) NOT NULL,"
                + "skills VARCHAR(255) NOT NULL DEFAULT '',"
                + "capacity INT NOT NULL DEFAULT 1,"
                + "status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',"
                + "peer_id VARCHAR(100) DEFAULT NULL,"
                + "status_since DATETIME DEFAULT NULL,"
                + "online_since DATETIME DEFAULT NULL,"
                + "last_active_at DATETIME DEFAULT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (agent_id),"
                + "KEY idx_agent_status (status)"
                + ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS routing_ticket ("
                + "ticket_id VARCHAR(64) NOT NULL,"
                + "biz_key VARCHAR(64) NOT NULL,"
                + "biz_date DATE DEFAULT NULL,"
                + "queue_id VARCHAR(64) NOT NULL,"
                + "skill_group VARCHAR(64) NOT NULL,"
                + "priority INT NOT NULL DEFAULT 0,"
                + "status VARCHAR(16) NOT NULL,"
                + "handler_type VARCHAR(16) NOT NULL,"
                + "agent_id VARCHAR(64) DEFAULT NULL,"
                + "enqueued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "ring_started_at DATETIME DEFAULT NULL,"
                + "answered_at DATETIME DEFAULT NULL,"
                + "completed_at DATETIME DEFAULT NULL,"
                + "expires_at DATETIME DEFAULT NULL,"
                + "snapshot_json MEDIUMTEXT DEFAULT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (ticket_id),"
                + "KEY idx_ticket_biz (biz_key),"
                + "KEY idx_ticket_status (status, enqueued_at),"
                + "KEY idx_ticket_agent (agent_id, status)"
                + ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS routing_ticket_event ("
                + "event_id BIGINT NOT NULL AUTO_INCREMENT,"
                + "ticket_id VARCHAR(64) NOT NULL,"
                + "event_type VARCHAR(32) NOT NULL,"
                + "agent_id VARCHAR(64) DEFAULT NULL,"
                + "occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "payload_json MEDIUMTEXT DEFAULT NULL,"
                + "PRIMARY KEY (event_id),"
                + "KEY idx_ticket_event (ticket_id, occurred_at),"
                + "KEY idx_event_agent (agent_id, occurred_at)"
                + ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS routing_agent_daily_stats ("
                + "stat_date DATE NOT NULL,"
                + "agent_id VARCHAR(64) NOT NULL,"
                + "online_ms BIGINT NOT NULL DEFAULT 0,"
                + "busy_ms BIGINT NOT NULL DEFAULT 0,"
                + "break_ms BIGINT NOT NULL DEFAULT 0,"
                + "service_ms BIGINT NOT NULL DEFAULT 0,"
                + "served_count INT NOT NULL DEFAULT 0,"
                + "success_count INT NOT NULL DEFAULT 0,"
                + "answered_count INT NOT NULL DEFAULT 0,"
                + "rejected_count INT NOT NULL DEFAULT 0,"
                + "avg_ring_ms BIGINT NOT NULL DEFAULT 0,"
                + "avg_service_ms BIGINT NOT NULL DEFAULT 0,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (stat_date, agent_id)"
                + ")");
        jdbc.update("INSERT IGNORE INTO routing_queue(queue_id, queue_name, skill_group, priority, strategy, "
                + "max_wait_ms, ring_timeout_ms, enabled) VALUES('default','默认队列','GENERAL',0,'LONGEST_IDLE',0,45000,1)");
    }
}
