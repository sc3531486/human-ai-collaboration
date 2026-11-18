package com.example.bank.routing.persistence;

import com.example.bank.routing.routing.AgentRegistry;
import com.example.bank.routing.routing.HandlerType;
import com.example.bank.routing.routing.QueueRoutingService;
import com.example.bank.routing.routing.QueueTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 启动加载：服务重启后恢复坐席资源与活动工单。
 *
 * <p>只恢复活动工单（WAITING/RINGING/ANSWERED）；终态工单留在表里供审计。
 * 恢复不写状态日志、不触发派单，避免重启把历史状态再记一遍。</p>
 */
@Component
public class RoutingPersistenceBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RoutingPersistenceBootstrap.class);

    private final AgentRegistry agents;
    private final QueueRoutingService routing;
    private final RoutingAgentRepository agentRepository;
    private final RoutingTicketRepository ticketRepository;

    public RoutingPersistenceBootstrap(AgentRegistry agents, QueueRoutingService routing,
                                       RoutingAgentRepository agentRepository,
                                       RoutingTicketRepository ticketRepository) {
        this.agents = agents;
        this.routing = routing;
        this.agentRepository = agentRepository;
        this.ticketRepository = ticketRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        int agentCount = 0;
        for (Map<String, Object> row : agentRepository.loadAll()) {
            agents.restore(
                    text(row.get("agent_id")),
                    text(row.get("agent_name")),
                    text(row.get("peer_id")),
                    java.util.Arrays.asList(splitSkills(text(row.get("skills")))),                    text(row.get("status")),
                    millis(row.get("status_since")),
                    millis(row.get("online_since")),
                    millis(row.get("last_active_at")),
                    number(row.get("capacity"), 1));
            agentCount++;
        }

        Map<String, Integer> serving = new HashMap<String, Integer>();
        int ticketCount = 0;
        for (QueueTicket ticket : ticketRepository.loadActive()) {
            routing.restoreTicket(ticket);
            if ("ANSWERED".equals(ticket.status)
                    && HandlerType.HUMAN.equals(ticket.handlerType)
                    && ticket.agentId != null && !ticket.agentId.trim().isEmpty()) {
                Integer before = serving.get(ticket.agentId);
                serving.put(ticket.agentId, before == null ? 1 : before + 1);
            }
            ticketCount++;
        }
        for (Map.Entry<String, Integer> entry : serving.entrySet()) {
            agents.restoreServing(entry.getKey(), entry.getValue());
        }
        log.info("路由状态恢复完成：坐席 {} 个，活动工单 {} 个", agentCount, ticketCount);
    }

    private String[] splitSkills(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        return value.split(",");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long millis(Object value) {
        return value == null ? 0L : JdbcTimes.millis(value);
    }

    private int number(Object value, int defaultValue) {
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }
}
