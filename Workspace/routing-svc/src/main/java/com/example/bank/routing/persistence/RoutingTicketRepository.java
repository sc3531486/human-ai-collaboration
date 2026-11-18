package com.example.bank.routing.persistence;

import com.example.bank.routing.routing.QueueTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 排队工单仓储：核心字段单独成列供查询，完整快照存 snapshot_json 供重启恢复。 */
@Service
public class RoutingTicketRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RoutingTicketRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void save(QueueTicket ticket) {
        String snapshot;
        try {
            snapshot = json.writeValueAsString(ticket);
        } catch (Exception error) {
            throw new IllegalStateException("工单快照序列化失败，ticketId=" + ticket.ticketId, error);
        }
        String bizKey = ticket.bizKey != null && !ticket.bizKey.trim().isEmpty()
                ? ticket.bizKey
                : (ticket.bizNo != null && !ticket.bizNo.trim().isEmpty() ? ticket.bizNo : ticket.processId);
        Date bizDate = parseDate(ticket.bizDate);
        int updated = jdbc.update("UPDATE routing_ticket SET biz_key=?, biz_date=?, queue_id=?, skill_group=?, "
                        + "priority=?, status=?, handler_type=?, agent_id=?, enqueued_at=?, ring_started_at=?, "
                        + "answered_at=?, completed_at=?, expires_at=?, snapshot_json=?, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE ticket_id=?",
                bizKey, bizDate, ticket.queueId, ticket.skillGroup, ticket.priority, ticket.status,
                ticket.handlerType, ticket.agentId, timestamp(ticket.enqueuedAt),
                timestamp(ticket.ringStartedAt), timestamp(ticket.answeredAt),
                timestamp(ticket.completedAt), timestamp(ticket.expiresAt), snapshot, ticket.ticketId);
        if (updated == 0) {
            jdbc.update("INSERT INTO routing_ticket(ticket_id, biz_key, biz_date, queue_id, skill_group, priority, "
                            + "status, handler_type, agent_id, enqueued_at, ring_started_at, answered_at, completed_at, "
                            + "expires_at, snapshot_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    ticket.ticketId, bizKey, bizDate, ticket.queueId, ticket.skillGroup, ticket.priority,
                    ticket.status, ticket.handlerType, ticket.agentId, timestamp(ticket.enqueuedAt),
                    timestamp(ticket.ringStartedAt), timestamp(ticket.answeredAt),
                    timestamp(ticket.completedAt), timestamp(ticket.expiresAt), snapshot);
        }
    }

    /** 只加载活动工单（WAITING/RINGING/ANSWERED）；终态工单保留在表里供审计。 */
    public List<QueueTicket> loadActive() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ticket_id, snapshot_json FROM routing_ticket "
                        + "WHERE status IN ('WAITING','RINGING','ANSWERED') ORDER BY enqueued_at");
        List<QueueTicket> tickets = new ArrayList<QueueTicket>();
        for (Map<String, Object> row : rows) {
            Object snapshot = row.get("snapshot_json");
            if (snapshot == null) {
                throw new IllegalStateException("活动工单缺少快照，无法恢复：" + row.get("ticket_id"));
            }
            try {
                tickets.add(json.readValue(String.valueOf(snapshot), QueueTicket.class));
            } catch (Exception error) {
                throw new IllegalStateException("活动工单快照解析失败：" + row.get("ticket_id"), error);
            }
        }
        return tickets;
    }

    public List<Map<String, Object>> listByStatus(String status) {
        return jdbc.queryForList("SELECT * FROM routing_ticket WHERE status=? ORDER BY enqueued_at", status);
    }

    private Date parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Date.valueOf(value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("bizDate 不是合法日期（应为 yyyy-MM-dd）：" + value, error);
        }
    }

    private Timestamp timestamp(long millis) {
        return millis > 0 ? new Timestamp(millis) : null;
    }
}
