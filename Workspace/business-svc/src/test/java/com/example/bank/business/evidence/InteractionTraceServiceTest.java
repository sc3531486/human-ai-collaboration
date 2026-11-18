package com.example.bank.business.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InteractionTraceServiceTest {
    private InteractionTraceService traces;
    @BeforeEach void initialize() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        traces = new InteractionTraceService(jdbc, new ObjectMapper()); traces.initialize();
    }
    @Test void repeatsHandoffsWithMonotonicSegmentsAndIsolatedOperationPages() {
        traces.append("BIZ-phases","a","p1","客户填单","CUSTOMER","FORM",Collections.singletonMap("text","first"),"");
        assertEquals(2,traces.switchPhase("BIZ-phases","HUMAN_AGENT","p1:human:1"));
        traces.append("BIZ-phases","b","p1","人工核验","AGENT","FORM",Collections.singletonMap("text","human"),"");
        assertEquals(3,traces.switchPhase("BIZ-phases","DIGITAL_HUMAN","p1:digital:2"));
        assertEquals(2,traces.switchPhase("BIZ-phases","HUMAN_AGENT","p1:human:1"));
        assertEquals(4,traces.switchPhase("BIZ-phases","HUMAN_AGENT","p1:human:3"));
        assertEquals(1,((List<?>)traces.listPhase("BIZ-phases",1,0,100).get("rows")).size());
        assertEquals(1,((List<?>)traces.listPhase("BIZ-phases",2,0,100).get("rows")).size());
        assertEquals(0,((List<?>)traces.listPhase("BIZ-phases",3,0,100).get("rows")).size());
        assertEquals(4,traces.phases("BIZ-phases").size());
        assertThrows(ResponseStatusException.class,()->traces.requirePhase("BIZ-phases",2,"DIGITAL_HUMAN"));
        traces.closePhases("BIZ-phases");assertNotNull(traces.phases("BIZ-phases").get(3).get("ended_at"));
    }
    private Map<String,Object> input() {
        Map<String,Object> input = new LinkedHashMap<>(); input.put("bizNo", "BIZ-chat");
        input.put("requestId", "request-1"); input.put("text", "先咨询一下再转人工"); input.put("peerId", "mobile-1"); return input;
    }
    @Test void retainsConversationBeforeAnyFlowExistsAndReplaysTheSameReply() {
        AtomicInteger calls = new AtomicInteger();
        Map<String,Object> reply = traces.chat(input(), () -> {
            calls.incrementAndGet(); assertEquals(1, ((List<?>)traces.list("BIZ-chat", 0, 100).get("rows")).size());
            Map<String,Object> result = new LinkedHashMap<>(); result.put("reply", "我可以帮您查询余额"); result.put("success", true); return result;
        });
        assertEquals(reply, traces.chat(input(), () -> { throw new AssertionError("must not call the model twice"); }));
        assertEquals(1, calls.get());
        List<Map<String,Object>> rows = (List<Map<String,Object>>)traces.list("BIZ-chat", 0, 100).get("rows");
        assertEquals("CUSTOMER", rows.get(0).get("actorRole"));
        assertEquals("DIGITAL_HUMAN", rows.get(1).get("actorRole"));
        assertEquals("", rows.get(0).get("processId"));
        assertEquals("先咨询一下再转人工", ((Map<?,?>)rows.get(0).get("payload")).get("text"));
    }
    @Test void cannotReplaceAnEarlierRequestWithDifferentText() {
        traces.chat(input(), () -> Collections.singletonMap("reply", "收到"));
        Map<String,Object> changed = input(); changed.put("text", "不同请求");
        assertThrows(ResponseStatusException.class, () -> traces.chat(changed, () -> Collections.emptyMap()));
    }
    @Test void preservesFailedAttemptsAlongsideTheOriginalCustomerInput() {
        assertThrows(IllegalStateException.class, () -> traces.chat(input(), () -> { throw new IllegalStateException("model unavailable"); }));
        List<Map<String,Object>> rows = (List<Map<String,Object>>)traces.list("BIZ-chat", 0, 100).get("rows");
        assertEquals(2, rows.size()); assertEquals("CHAT_FAILED", rows.get(1).get("eventType"));
        assertEquals(false, traces.chat(input(), () -> Collections.emptyMap()).get("success"));
    }
    @Test void paginatesWithoutLosingOlderInteractions() {
        for (int i = 0; i < 5; i++) traces.append("BIZ-chat", "event-" + i, "p1", "数字人收集信息", "CUSTOMER", "FORM", Collections.singletonMap("index", i), "");
        Map<String,Object> first = traces.list("BIZ-chat", 0, 2);
        assertEquals(true, first.get("hasMore"));
        Map<String,Object> second = traces.list("BIZ-chat", ((Number)first.get("nextId")).longValue(), 200);
        assertEquals(3, ((List<?>)second.get("rows")).size()); assertEquals(false, second.get("hasMore"));
    }

    @Test void missingReplyOrConsentPreventsCompleteInteractionEvidence() {
        traces.append("BIZ-chat", "consent-1", "", "", "CUSTOMER", "CONSENT_AGREED", Collections.singletonMap("accepted", true), "");
        traces.append("BIZ-chat", "pending:request", "", "", "CUSTOMER", "CHAT_REQUEST", Collections.singletonMap("text", "咨询"), "");
        assertFalse(traces.conversationComplete("BIZ-chat"));
        traces.append("BIZ-chat", "pending:reply", "", "", "DIGITAL_HUMAN", "CHAT_REPLY", Collections.singletonMap("reply", "回复"), "");
        assertTrue(traces.conversationComplete("BIZ-chat"));
        traces.chat(input(), () -> Collections.singletonMap("reply", "已回复"));
        assertTrue(traces.conversationComplete("BIZ-chat"));
    }

    @Test void agentOnlyLocalWorkflowDoesNotRequireAnInventedCustomerConsent() {
        traces.append("BIZ-agent", "photo-1", "p1", "人工核验", "AGENT", "CAPTURE_SAVED", Collections.singletonMap("fileHash", "hash"), "");
        assertTrue(traces.conversationComplete("BIZ-agent"));
    }
}
