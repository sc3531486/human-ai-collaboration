package com.example.bank.message.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 消息产品表初始化：启动时幂等建表，单测用 H2 MySQL 模式可直接跑。
 *
 * <p>消息记录是投递事实（ACCEPTED/DELIVERED/FAILED），模板是产品配置；
 * 两者都属于 message-svc，不落 business 库。</p>
 */
@Component
public class MessageSchemaInitializer {

    private final JdbcTemplate jdbc;

    public MessageSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS message_template ("
                + "template_code VARCHAR(64) NOT NULL COMMENT '模板编码',"
                + "template_name VARCHAR(100) NOT NULL COMMENT '模板名称',"
                + "event_type VARCHAR(64) DEFAULT NULL COMMENT '适用事件类型',"
                + "title VARCHAR(255) DEFAULT NULL COMMENT '消息标题',"
                + "content_template MEDIUMTEXT NOT NULL COMMENT '消息内容模板',"
                + "enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1启用，0停用',"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "PRIMARY KEY (template_code)"
                + ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS message_record ("
                + "message_id VARCHAR(256) NOT NULL COMMENT '消息ID：eventId:audience:subject，无eventId时为UUID',"
                + "event_id VARCHAR(128) DEFAULT NULL COMMENT '来源事件ID',"
                + "audience VARCHAR(16) NOT NULL COMMENT '接收方类型：agent/customer',"
                + "subject_id VARCHAR(100) NOT NULL COMMENT '接收方标识',"
                + "event_type VARCHAR(64) DEFAULT NULL COMMENT '事件类型',"
                + "payload_json MEDIUMTEXT NOT NULL COMMENT '事件信封JSON',"
                + "status VARCHAR(16) NOT NULL COMMENT '状态：ACCEPTED/DELIVERED/FAILED',"
                + "attempts INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',"
                + "last_error VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败原因',"
                + "accepted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '受理时间',"
                + "delivered_at DATETIME DEFAULT NULL COMMENT '交付时间',"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "PRIMARY KEY (message_id),"
                + "KEY idx_message_subject (subject_id, accepted_at),"
                + "KEY idx_message_status (status, accepted_at)"
                + ")");
    }
}
