package com.example.bank.business.port;

import com.example.bank.business.contract.Agent;
import com.example.bank.business.contract.DigitalHuman;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link AgentPort} 的 HTTP 实现：转发到排队路由服务的名册接口。 */
@Component
public class HttpAgentAdapter implements AgentPort {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpAgentAdapter(RestTemplate http, @Value("${business.routing-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> setStatus(String agentId, String name, String peerId, String skills, String status) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", name == null ? "" : name);
        body.put("peerId", peerId == null ? "" : peerId);
        // 对端按逗号切分技能组；空串切出 [""]，其 register 会过滤掉空项并保留既有技能组
        body.put("skills", skills == null ? "" : skills);
        body.put("status", status == null ? "" : status);
        Map<String, Object> result = http.postForObject(baseUrl + "/internal/agent/{agentId}/status",
                json(body), Map.class, agentId);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    @Override
    public void touch(String agentId) {
        http.postForObject(baseUrl + "/internal/agent/{agentId}/touch", null, Void.class, agentId);
    }

    @Override
    public void offline(String agentId) {
        http.postForObject(baseUrl + "/internal/agent/{agentId}/offline", null, Void.class, agentId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> summary(String agentId) {
        Map<String, Object> result = http.getForObject(baseUrl + "/internal/agent/{agentId}/calls",
                Map.class, agentId);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    @Override
    public List<Agent> all() {
        return getList("/internal/agents", new ParameterizedTypeReference<List<Agent>>() { });
    }

    @Override
    public List<DigitalHuman> digitalHumans() {
        return getList("/internal/digital-humans", new ParameterizedTypeReference<List<DigitalHuman>>() { });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> statsToday(String agentId) {
        Map<String, Object> result = http.getForObject(baseUrl + "/internal/agent/{agentId}/stats/today",
                Map.class, agentId);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    @Override
    public void recordSuccess(String agentId) {
        http.postForObject(baseUrl + "/internal/agent/{agentId}/success", null, Void.class, agentId);
    }

    private <T> List<T> getList(String path, ParameterizedTypeReference<List<T>> type) {
        ResponseEntity<List<T>> response = http.exchange(baseUrl + path, HttpMethod.GET, null, type);
        List<T> body = response.getBody();
        return body == null ? Collections.<T>emptyList() : body;
    }

    /** 显式声明 JSON 请求体：Map 直接作为 body 时默认按表单编码，中文会乱码。 */
    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(body, headers);
    }
}
