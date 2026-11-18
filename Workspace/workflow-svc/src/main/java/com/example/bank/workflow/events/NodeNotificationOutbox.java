package com.example.bank.workflow.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import javax.annotation.PostConstruct;
import java.util.*;

/** 节点通知持久化后异步投递；消息故障只重试原事件，不改变业务路径。 */
@Service
public class NodeNotificationOutbox {
    private final JdbcTemplate jdbc; private final RestTemplate http;private final ObjectMapper json;private final String businessUrl;
    public NodeNotificationOutbox(JdbcTemplate jdbc,RestTemplate http,ObjectMapper json,@Value("${workflow.business-base-url}") String businessUrl) { this.jdbc=jdbc;this.http=http;this.json=json;this.businessUrl=businessUrl; }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_node_notification(delivery_id VARCHAR(400) PRIMARY KEY,audience VARCHAR(16) NOT NULL,subject_id VARCHAR(100) NOT NULL,payload MEDIUMTEXT NOT NULL,delivered BOOLEAN NOT NULL DEFAULT FALSE,attempts INT NOT NULL DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }
    public void enqueue(String audience,String subject,Map<String,Object> event) {
        if(subject==null || subject.trim().isEmpty()) return;
        try {
            String encoded=json.writeValueAsString(event);
            String id=event.get("eventId")==null?UUID.nameUUIDFromBytes(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString():String.valueOf(event.get("eventId"));
            jdbc.update("INSERT IGNORE INTO workflow_node_notification(delivery_id,audience,subject_id,payload) VALUES(?,?,?,?)",id+":"+audience+":"+subject,audience,subject,encoded);
        } catch(java.io.IOException error) { throw new IllegalStateException("节点通知序列化失败",error); }
    }
    @Scheduled(fixedDelayString="${workflow.node-message-delay-ms:750}")
    public void publish() {
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM workflow_node_notification WHERE delivered=FALSE ORDER BY created_at,delivery_id LIMIT 30")) {
            try {
                Map<?,?> payload=json.readValue(String.valueOf(row.get("payload")),Map.class);
                Map<?,?> receipt=http.postForObject(businessUrl+"/internal/notifications/{audience}/{subject}",payload,Map.class,row.get("audience"),row.get("subject_id"));
                if(receipt==null || !Boolean.TRUE.equals(receipt.get("delivered"))) throw new IllegalStateException("通知未交付");
                jdbc.update("UPDATE workflow_node_notification SET delivered=TRUE WHERE delivery_id=?",row.get("delivery_id"));
            } catch(Exception error) {
                jdbc.update("UPDATE workflow_node_notification SET attempts=attempts+1 WHERE delivery_id=?",row.get("delivery_id"));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("节点通知待重试 deliveryId={}",row.get("delivery_id"));
            }
        }
    }
}
