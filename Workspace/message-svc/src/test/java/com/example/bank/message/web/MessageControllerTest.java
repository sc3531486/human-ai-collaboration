package com.example.bank.message.web;

import com.example.bank.message.auth.MessagePrincipal;
import com.example.bank.message.auth.MessageTokenService;
import com.example.bank.message.config.MessageProperties;
import com.example.bank.message.presence.ConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageControllerTest {

    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final MessageController controller = new MessageController(
            new SimpMessagingTemplate(new MessageChannel() {
                @Override
                public boolean send(Message<?> message) {
                    return true;
                }

                @Override
                public boolean send(Message<?> message, long timeout) {
                    return true;
                }
            }),
            new MessageProperties(),
            new MessageTokenService(new MessageProperties()),
            registry);

    @Test
    void tokenRejectsBothAgentAndCustomerId() {
        Map<String, String> body = new HashMap<String, String>();
        body.put("agentId", "agent-001");
        body.put("customerId", "customer-001");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.token(body));

        assertEquals(400, ex.getStatus().value());
    }

    @Test
    void publishRejectsMissingEventBody() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.publishAgent("agent-001", null));

        assertEquals(400, ex.getStatus().value());
    }

    @Test
    void deliveredOnlyAfterPersonalQueueSubscribed() {
        Map<String, Object> event = new HashMap<String, Object>();
        event.put("type", "NODE_STATE");

        assertEquals(Boolean.FALSE, controller.publishCustomer("customer-001", event).get("delivered"));

        registry.register("session-1", new MessagePrincipal("customer-001", "customer"));
        registry.markSubscribed("session-1");

        assertEquals(Boolean.TRUE, controller.publishCustomer("customer-001", event).get("delivered"));
    }
}
