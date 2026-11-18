package com.example.bank.message;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.example.bank.message.auth.MessagePrincipal;
import com.example.bank.message.auth.MessageTokenService;
import com.example.bank.message.config.MessageProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageTokenServiceTest {

    @Test
    void issuesAndAuthenticatesAgentToken() {
        MessageProperties properties = new MessageProperties();
        properties.setTokenSecret("test-secret");
        MessagePrincipal principal = new MessageTokenService(properties)
                .authenticate(new MessageTokenService(properties).issue("agent-001", "agent"));

        assertEquals("agent-001", principal.getSubject());
        assertEquals("agent", principal.getRole());
    }

    @Test
    void rejectsTokenSignedByAnotherSecret() {
        MessageProperties first = new MessageProperties();
        first.setTokenSecret("first-secret");
        MessageProperties second = new MessageProperties();
        second.setTokenSecret("second-secret");
        String token = new MessageTokenService(first).issue("customer-001", "customer");

        assertThrows(RuntimeException.class, () -> new MessageTokenService(second).authenticate(token));
    }

    @Test
    void rejectsTokenWithoutRoleClaimInsteadOfDefaultingToUnknown() {
        MessageProperties properties = new MessageProperties();
        properties.setTokenSecret("test-secret");
        String token = JWT.create()
                .withSubject("agent-002")
                .sign(Algorithm.HMAC256("test-secret"));

        assertThrows(IllegalArgumentException.class,
                () -> new MessageTokenService(properties).authenticate(token));
    }

    @Test
    void refusesToIssueTokenWithoutRole() {
        MessageProperties properties = new MessageProperties();
        properties.setTokenSecret("test-secret");
        MessageTokenService service = new MessageTokenService(properties);

        assertThrows(IllegalArgumentException.class, () -> service.issue("agent-002", " "));
    }
}
