package com.example.bank.business.application;

import com.example.bank.business.port.MediaPort;
import com.example.bank.business.port.RoutingPort;
import com.example.bank.business.service.EvaluationService;
import com.example.bank.business.service.SmsVerificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CallAndEvaluationTest {
    final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:call-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
    final MediaPort media = mock(MediaPort.class);
    final RoutingPort routing = mock(RoutingPort.class);
    final com.example.bank.business.service.ServiceBusinessCatalog catalog=new com.example.bank.business.service.ServiceBusinessCatalog(jdbc);
    final CallSessionService calls = new CallSessionService(jdbc, new ObjectMapper(), routing, media,catalog);

    Map<String,Object> register() {
        calls.initialize();
        catalog.initialize();
        Map<String,Object> context = new HashMap<>();
        context.put("customer_id", "customer"); context.put("mobile_peer_id", "customer-peer");
        context.put("agent_session_id", "agent"); context.put("agent_peer_id", "agent-peer");
        context.put("biz_no", "BIZ-CALL");
        return calls.register("parent", context, "original-room");
    }
    @Test void authorizationJoinsOriginalRoomAndLeavingDoesNotEndConversation() {
        Map<String,Object> call = register();
        assertThrows(ResponseStatusException.class, () -> calls.joinAuthorization("parent", "child", "agent", "same"));
        assertEquals("original-room", calls.joinAuthorization("parent", "child", "reviewer", "review-peer").get("roomId"));
        assertEquals(3, ((List<?>)calls.byProcess("parent").get("participants")).size());
        assertEquals("AUTHORIZER", calls.byProcess("child").get("role"));
        assertThrows(ResponseStatusException.class, () -> calls.end((String)call.get("callId"), "reviewer", "AGENT"));
        calls.leaveAuthorization("child", "reviewer");
        assertEquals("OPEN", calls.byProcess("parent").get("status"));
        assertEquals(2, ((List<?>)calls.byProcess("parent").get("participants")).size());
        verify(routing).complete("child"); verify(media, never()).hangupRoom(anyString(), anyMap());
    }
    @Test void hangupUsesMediaServiceAndRetriesDurableEventWithoutClosingEarly() {
        Map<String,Object> call = register();
        verifyNoInteractions(media, routing);
        String id = (String)call.get("callId");
        calls.end(id, "customer", "CUSTOMER");
        calls.end(id, "customer", "CUSTOMER");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM biz_call_event", Integer.class));
        doThrow(new IllegalStateException("unreachable")).doNothing().when(media).hangupRoom(anyString(), anyMap());
        calls.deliver(); verifyNoInteractions(routing);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_call_event WHERE delivered=TRUE", Integer.class));
        calls.deliver(); verify(routing).complete("parent");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM biz_call_event WHERE delivered=TRUE", Integer.class));
    }
    @Test void evaluationQueriesConfiguredCommonAndStarTagsAndRejectsWrongScope() {
        Map<String,Object> call = register(); EvaluationService service = new EvaluationService(jdbc, calls, new ObjectMapper()); service.initialize();
        jdbc.update("UPDATE biz_evaluation_tag SET label='数据库标签' WHERE tag_code='SERVICE_PATIENT'");
        assertTrue(service.tags(null).stream().anyMatch(tag -> "数据库标签".equals(tag.get("label"))));
        Map<String,Object> input = new HashMap<>(); input.put("callId", call.get("callId")); input.put("customerId", "customer"); input.put("stars", 5);
        input.put("tagIds", Collections.singletonList(service.tags(1).get(0).get("id")));
        assertThrows(ResponseStatusException.class, () -> service.submit(input));
        calls.end((String)call.get("callId"), "agent", "AGENT");
        assertThrows(ResponseStatusException.class, () -> service.submit(input));
        input.put("tagIds", Collections.singletonList(service.tags(5).get(0).get("id")));
        assertEquals(true, service.submit(input).get("saved")); assertEquals(true, service.submit(input).get("saved"));
        input.put("stars", 6); assertThrows(ResponseStatusException.class, () -> service.submit(input));
        input.put("stars", 5); input.put("customerId", "other"); assertThrows(ResponseStatusException.class, () -> service.submit(input));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM biz_call_evaluation", Integer.class));
    }
    @Test void smsRequiresCorrectCodeAndBusinessBoundVerificationReceipt() {
        SmsVerificationService sms = new SmsVerificationService(jdbc); sms.initialize();
        jdbc.update("UPDATE biz_mock_setting SET setting_value='654321' WHERE setting_key='SMS_CODE'");
        Map<String,Object> input = new HashMap<>(); input.put("processId", "p"); input.put("bizNo", "B"); input.put("customerId", "customer"); input.put("cardId", "card");
        Map<String,Object> challenge = sms.send(input); input.put("challengeId", challenge.get("smsChallengeId")); input.put("code", "123456");
        assertEquals(false, sms.verify(input).get("smsPassed")); assertThrows(ResponseStatusException.class, () -> sms.requireVerified(input));
        input.put("code", "654321"); Map<String,Object> verified = sms.verify(input); assertEquals(true, verified.get("smsPassed"));
        input.put("smsVerificationId", verified.get("smsVerificationId")); sms.requireVerified(input);
        input.put("cardId", "another"); assertThrows(ResponseStatusException.class, () -> sms.requireVerified(input));
    }
}
