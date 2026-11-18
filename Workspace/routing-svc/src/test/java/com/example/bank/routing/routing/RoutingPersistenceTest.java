package com.example.bank.routing.routing;

import com.example.bank.routing.persistence.RoutingAgentRepository;
import com.example.bank.routing.persistence.RoutingDailyStatsRepository;
import com.example.bank.routing.persistence.RoutingPersistenceBootstrap;
import com.example.bank.routing.persistence.RoutingSchemaInitializer;
import com.example.bank.routing.persistence.RoutingTicketEventRepository;
import com.example.bank.routing.persistence.RoutingTicketRepository;
import com.example.bank.routing.port.ProcessPort;
import com.example.bank.routing.port.ProductEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class RoutingPersistenceTest {

    private JdbcTemplate jdbc;
    private RoutingAgentRepository agentRepository;
    private RoutingTicketRepository ticketRepository;
    private RoutingTicketEventRepository eventRepository;
    private RoutingDailyStatsRepository statsRepository;
    private AgentRegistry agents;
    private QueueRoutingService routing;
    private ProcessPort process;
    private ProductEventPublisher events;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:routing-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        new RoutingSchemaInitializer(jdbc).initialize();

        JdbcAgentStatusLog statusLog = new JdbcAgentStatusLog(jdbc);
        statusLog.initialize();
        ObjectMapper json = new ObjectMapper();
        agentRepository = new RoutingAgentRepository(jdbc);
        ticketRepository = new RoutingTicketRepository(jdbc, json);
        eventRepository = new RoutingTicketEventRepository(jdbc, json);
        statsRepository = new RoutingDailyStatsRepository(jdbc);

        agents = new AgentRegistry();
        agents.setStatusLog(statusLog);
        agents.setAgentRepository(agentRepository);
        agents.setDailyStatsRepository(statsRepository);
        process = mock(ProcessPort.class);
        events = mock(ProductEventPublisher.class);
        routing = routing(agents);
    }

    private QueueRoutingService routing(AgentRegistry registry) {
        QueueRoutingService service = new QueueRoutingService(
                Arrays.<RoutingPool>asList(new AgentRoutingPool(registry), new DigitalHumanRoutingPool(new DigitalHumanRegistry())),
                events, process, registry, 30000L);
        service.setTicketRepository(ticketRepository);
        service.setTicketEventRepository(eventRepository);
        service.setDailyStatsRepository(statsRepository);
        return service;
    }

    private QueueRequest request(String processId, String bizKey) {
        QueueRequest request = new QueueRequest();
        request.processId = processId;
        request.bizKey = bizKey;
        request.bizDate = "2026-10-19";
        request.queueId = "default";
        request.handlerType = HandlerType.HUMAN;
        request.handledNode = "human";
        request.skillGroup = "GOLD";
        return request;
    }

    @Test
    void routePersistsTicketAgentAndReloadsActiveTicket() {
        agents.register("a1", "坐席一", "peer1", Arrays.asList("GOLD"));
        agents.setStatus("a1", "ONLINE");

        QueueTicket ticket = routing.route(request("p1", "BIZ-1"));
        assertEquals("RINGING", ticket.status);
        assertEquals("BIZ-1", ticket.bizKey);
        assertEquals(1, ticketRepository.listByStatus("RINGING").size());
        assertEquals(1, agentRepository.loadAll().size());

        AgentRegistry reloadedAgents = new AgentRegistry();
        reloadedAgents.setStatusLog(new JdbcAgentStatusLog(jdbc));
        QueueRoutingService reloaded = routing(reloadedAgents);
        new RoutingPersistenceBootstrap(reloadedAgents, reloaded, agentRepository, ticketRepository).load();

        assertNotNull(reloadedAgents.get("a1"));
        assertEquals("ONLINE", reloadedAgents.get("a1").status);
        assertNotNull(reloaded.byProcess("p1"));
        assertEquals("RINGING", reloaded.byProcess("p1").status);
        assertEquals("BIZ-1", reloaded.byProcess("p1").bizKey);
    }

    @Test
    void answeredTicketRestoresAgentServingAfterRestart() {
        agents.register("a1", "坐席一", "peer1", Arrays.asList("GOLD"));
        agents.setStatus("a1", "ONLINE");
        routing.route(request("p1", "BIZ-1"));
        routing.answer("a1", "p1", "peer1");

        AgentRegistry reloadedAgents = new AgentRegistry();
        reloadedAgents.setStatusLog(new JdbcAgentStatusLog(jdbc));
        QueueRoutingService reloaded = routing(reloadedAgents);
        new RoutingPersistenceBootstrap(reloadedAgents, reloaded, agentRepository, ticketRepository).load();

        assertEquals("ANSWERED", reloaded.byProcess("p1").status);
        assertEquals("BUSY", reloadedAgents.get("a1").status);
        assertEquals(1, reloadedAgents.get("a1").serving);
    }

    @Test
    void answerAndCompletePersistEventsAndDailyStats() {
        agents.register("a1", "坐席一", "peer1", Arrays.asList("GOLD"));
        agents.setStatus("a1", "ONLINE");

        QueueTicket ticket = routing.route(request("p1", "BIZ-1"));
        routing.answer("a1", "p1", "peer1");
        assertEquals(1, ticketRepository.listByStatus("ANSWERED").size());

        routing.complete("p1");
        assertEquals(1, ticketRepository.listByStatus("COMPLETED").size());

        List<Map<String, Object>> events = eventRepository.forTicket(ticket.ticketId);
        List<String> types = events.stream().map(row -> String.valueOf(row.get("event_type"))).collect(java.util.stream.Collectors.toList());
        assertEquals(true, types.contains("ENQUEUED"));
        assertEquals(true, types.contains("RINGING"));
        assertEquals(true, types.contains("ANSWERED"));
        assertEquals(true, types.contains("COMPLETED"));

        Map<String, Object> stats = jdbc.queryForMap(
                "SELECT answered_count, served_count FROM routing_agent_daily_stats WHERE agent_id='a1'");
        assertEquals(1, ((Number) stats.get("answered_count")).intValue());
        assertEquals(1, ((Number) stats.get("served_count")).intValue());
    }
}
