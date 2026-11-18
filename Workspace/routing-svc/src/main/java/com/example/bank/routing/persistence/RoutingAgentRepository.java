package com.example.bank.routing.persistence;

import com.example.bank.routing.routing.AgentRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 坐席/接待资源仓储：路由侧属性与在线状态，不复制人员档案。 */
@Service
public class RoutingAgentRepository {

    private final JdbcTemplate jdbc;

    public RoutingAgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(AgentRegistry.Agent agent) {
        String name = agent.name == null ? "" : agent.name;
        String skills = agent.skills == null ? "" : String.join(",", agent.skills);
        Timestamp statusSince = timestamp(agent.statusSince);
        Timestamp onlineSince = timestamp(agent.onlineSince);
        Timestamp lastActive = timestamp(agent.lastActive);
        int updated = jdbc.update("UPDATE routing_agent SET agent_name=?, skills=?, capacity=?, status=?, "
                        + "peer_id=?, status_since=?, online_since=?, last_active_at=?, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE agent_id=?",
                name, skills, agent.capacity, agent.status, agent.peerId,
                statusSince, onlineSince, lastActive, agent.agentId);
        if (updated == 0) {
            jdbc.update("INSERT INTO routing_agent(agent_id, agent_name, skills, capacity, status, peer_id, "
                            + "status_since, online_since, last_active_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    agent.agentId, name, skills, agent.capacity, agent.status, agent.peerId,
                    statusSince, onlineSince, lastActive);
        }
    }

    public List<Map<String, Object>> loadAll() {
        return jdbc.queryForList("SELECT * FROM routing_agent ORDER BY agent_id");
    }

    private Timestamp timestamp(long millis) {
        return millis > 0 ? new Timestamp(millis) : null;
    }
}
