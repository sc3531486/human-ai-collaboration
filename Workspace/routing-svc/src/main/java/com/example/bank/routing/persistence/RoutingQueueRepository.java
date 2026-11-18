package com.example.bank.routing.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 队列配置仓储：只读，供路由匹配与运维查询使用。 */
@Service
public class RoutingQueueRepository {

    private final JdbcTemplate jdbc;

    public RoutingQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> loadAll() {
        return jdbc.queryForList("SELECT * FROM routing_queue ORDER BY priority DESC, queue_id");
    }

    public Map<String, Object> find(String queueId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM routing_queue WHERE queue_id=?", queueId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
