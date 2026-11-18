package com.example.bank.business.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link SessionPort} 的 HTTP 实现：转发到流程引擎服务的会话接口。 */
@Component
public class HttpSessionAdapter implements SessionPort {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpSessionAdapter(RestTemplate http, @Value("${business.workflow-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public List<Map<String, Object>> queue(String agentId) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/process/session/queue")
                .queryParam("agentId", agentId == null ? "" : agentId)
                .toUriString();
        ResponseEntity<List<Map<String, Object>>> response = http.exchange(url, HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        return response.getBody() == null ? Collections.<Map<String, Object>>emptyList() : response.getBody();
    }

    @Override
    public void claim(String processId, String agentId, String peerId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("agentId", agentId == null ? "" : agentId);
        body.put("peerId", peerId == null ? "" : peerId);
        // "已被他人接听"与"已离开人工节点"的 409 原样上抛，由调用方转成前端提示
        http.postForObject(baseUrl + "/internal/process/{processId}/session/claim",
                json(body), Void.class, processId);
    }

    @Override
    public void releaseClaim(String processId, String agentId) {
        http.postForObject(baseUrl + "/internal/process/{processId}/session/release", json(
                Collections.<String,Object>singletonMap("agentId",agentId)), Void.class, processId);
    }

    @Override
    public void setCallMode(String processId, String agentId, String mode) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("agentId", agentId == null ? "" : agentId);
        body.put("mode", mode == null ? "" : mode);
        // 归属校验在对端与写入同一次调用里完成，这里的 409/400 都是业务结论，照原样透出
        http.postForObject(baseUrl + "/internal/process/{processId}/call",
                json(body), Void.class, processId);
    }

    @Override
    public void requireOwner(String processId, String agentId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("agentId", agentId == null ? "" : agentId);
        // 归属校验的 4xx 原样上抛：调用方靠它区分"请先接听并绑定"与"服务不可用"
        http.postForObject(baseUrl + "/internal/process/{processId}/session/require-owner",
                json(body), Void.class, processId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> close(String processId) {
        Map<String, Object> result = http.postForObject(
                baseUrl + "/internal/process/{processId}/session/close", null, Map.class, processId);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    /** 显式声明 JSON 请求体：Map 直接作为 body 时默认按表单编码，中文会乱码。 */
    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(body, headers);
    }
}
