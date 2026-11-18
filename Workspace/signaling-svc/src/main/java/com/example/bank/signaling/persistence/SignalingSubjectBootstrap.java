package com.example.bank.signaling.persistence;

import com.example.bank.signaling.presence.ConnectedSubjectRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动恢复：把数据库中已签发过令牌的主体重新纳入 presence 扫描。
 *
 * <p>不恢复会漏掉“重启前已签发、重启后还没换新令牌”的主体，导致它们断线后
 * presence 永远不判定。已上报状态一并恢复，避免重复上报同一断线事实。</p>
 */
@Component
public class SignalingSubjectBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SignalingSubjectBootstrap.class);

    private final SignalingSubjectRepository subjects;
    private final ConnectedSubjectRegistry registry;

    public SignalingSubjectBootstrap(SignalingSubjectRepository subjects, ConnectedSubjectRegistry registry) {
        this.subjects = subjects;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        List<Map<String, Object>> rows = subjects.listAll();
        for (Map<String, Object> row : rows) {
            long registeredAt = JdbcTimes.millis(row.get("registered_at"));
            registry.restore(String.valueOf(row.get("subject_id")), String.valueOf(row.get("role")),
                    registeredAt, truthy(row.get("reported")));
        }
        if (!rows.isEmpty()) {
            log.info("已从数据库恢复 {} 个信令主体", rows.size());
        }
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }
}
