package com.example.bank.message.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "message")
public class MessageProperties {

    private String websocketEndpoint = "/ws";
    private String wsUrl = "ws://localhost:8085/ws";
    private String tokenSecret = "change-me-in-production";
    private long tokenTtlSeconds = 3600L;
    private String allowedOriginPatterns = "http://localhost:5173";
    private PresenceProperties presence = new PresenceProperties();

    public String getWebsocketEndpoint() {
        return websocketEndpoint;
    }

    public void setWebsocketEndpoint(String websocketEndpoint) {
        this.websocketEndpoint = websocketEndpoint;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public String getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(String allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    public PresenceProperties getPresence() {
        return presence;
    }

    public void setPresence(PresenceProperties presence) {
        this.presence = presence;
    }

    public static class PresenceProperties {
        private String businessBaseUrl = "http://localhost:8080";
        private int connectTimeoutMs = 1000;
        private int readTimeoutMs = 2000;

        public String getBusinessBaseUrl() {
            return businessBaseUrl;
        }

        public void setBusinessBaseUrl(String businessBaseUrl) {
            this.businessBaseUrl = businessBaseUrl;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
