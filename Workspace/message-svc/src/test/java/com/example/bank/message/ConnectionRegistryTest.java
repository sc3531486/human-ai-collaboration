package com.example.bank.message;

import com.example.bank.message.auth.MessagePrincipal;
import com.example.bank.message.presence.ConnectionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionRegistryTest {

    @Test
    void onlyLastSessionProducesDisconnectCandidate() {
        ConnectionRegistry registry = new ConnectionRegistry();
        MessagePrincipal principal = new MessagePrincipal("agent-001", "agent");
        registry.register("session-a", principal);
        registry.register("session-b", principal);

        assertTrue(registry.isConnected("agent-001"));
        assertFalse(registry.remove("session-a") != null);
        assertNotNull(registry.remove("session-b"));
        assertFalse(registry.isConnected("agent-001"));
    }

    @Test
    void rejectsNullRegistrationArguments() {
        ConnectionRegistry registry = new ConnectionRegistry();
        MessagePrincipal principal = new MessagePrincipal("agent-001", "agent");

        assertThrows(IllegalArgumentException.class, () -> registry.register(null, principal));
        assertThrows(IllegalArgumentException.class, () -> registry.register("session-a", null));
    }

    @Test
    void deliveryReceiptFollowsSubscriptionInsteadOfConnection() {
        ConnectionRegistry registry = new ConnectionRegistry();
        MessagePrincipal principal = new MessagePrincipal("agent-001", "agent");
        registry.register("session-a", principal);

        assertFalse(registry.isSubscribed("agent-001"));
        registry.markSubscribed("session-a");
        assertTrue(registry.isSubscribed("agent-001"));
        registry.remove("session-a");
        assertFalse(registry.isSubscribed("agent-001"));
    }

    @Test
    void rejectsSubscriptionRegisteredBeforeConnection() {
        ConnectionRegistry registry = new ConnectionRegistry();

        assertThrows(IllegalStateException.class, () -> registry.markSubscribed("unknown-session"));
    }
}
