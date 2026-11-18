package com.example.bank.message.presence;

import com.example.bank.message.config.MessageProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HttpPresenceEventAdapter implements PresenceEventPort {

    private final RestTemplate http;
    private final MessageProperties properties;

    public HttpPresenceEventAdapter(@Qualifier("presenceEventRestTemplate") RestTemplate http,
                                    MessageProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public void disconnected(String agentId, String reason, String source, String channel) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("domain", "presence");
        event.put("type", "AGENT_DISCONNECTED");
        event.put("agentId", agentId);
        event.put("at", Instant.now().toString());
        event.put("reason", reason);
        event.put("source", source);
        event.put("channel", channel);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            http.postForObject(properties.getPresence().getBusinessBaseUrl()
                    + "/internal/presence/disconnected", new HttpEntity<Map<String, Object>>(event, headers), Map.class);
        } catch (RuntimeException ex) {
            // 失败不再降级为"只记日志"：带 agentId、endpoint 与原始原因抛给调用方。
            // 是否重试由调用方按契约显式决定（当前契约：不重试）。
            throw new PresenceEventDeliveryException(agentId,
                    properties.getPresence().getBusinessBaseUrl() + "/internal/presence/disconnected", ex);
        }
    }
}
