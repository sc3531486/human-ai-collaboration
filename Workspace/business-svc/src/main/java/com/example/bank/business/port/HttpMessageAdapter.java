package com.example.bank.business.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** MessagePort 的 HTTP 实现：business-svc → message-svc。 */
@Component
public class HttpMessageAdapter implements MessagePort {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpMessageAdapter(RestTemplate http, @Value("${business.message-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> token(String agentId, String customerId) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("agentId", agentId == null ? "" : agentId);
        body.put("customerId", customerId == null ? "" : customerId);
        Map<String, Object> result = http.postForObject(baseUrl + "/internal/token", json(body), Map.class);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    @Override
    public void publishAgent(String agentId, Map<String, Object> event) {
        publish("/internal/agent/{subject}/publish", agentId, event);
    }

    @Override
    public void publishCustomer(String customerId, Map<String, Object> event) {
        publish("/internal/customer/{subject}/publish", customerId, event);
    }

    private void publish(String path, String subject, Map<String, Object> event) {
        if (subject == null || subject.trim().isEmpty()) return;
        http.postForObject(baseUrl + path, json(event == null
                ? Collections.<String, Object>emptyMap() : event), Void.class, subject);
    }

    private HttpEntity<?> json(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Object>(body, headers);
    }
}
