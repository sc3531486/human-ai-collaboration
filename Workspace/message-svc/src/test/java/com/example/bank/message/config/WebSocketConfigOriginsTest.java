package com.example.bank.message.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebSocketConfigOriginsTest {

    @Test
    void parsesConfiguredOrigins() {
        String[] origins = WebSocketConfig.origins("http://localhost:5173 , https://bank.example.com");

        assertArrayEquals(new String[]{"http://localhost:5173", "https://bank.example.com"}, origins);
    }

    @Test
    void rejectsMissingOriginConfigurationInsteadOfWildcardFallback() {
        assertThrows(IllegalStateException.class, () -> WebSocketConfig.origins(null));
        assertThrows(IllegalStateException.class, () -> WebSocketConfig.origins("  "));
        assertThrows(IllegalStateException.class, () -> WebSocketConfig.origins(" , "));
    }
}
