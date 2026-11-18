package com.example.bank.routing.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/** 会话上下文与接听动作由 business-svc 提供，路由不直接访问流程或媒体服务。 */
@Component
public class HttpProcessAdapter implements ProcessPort {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpProcessAdapter(RestTemplate http, @Value("${routing.business-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override public Map<String,Object> variables(String processId){
        try{return http.getForObject(baseUrl+"/internal/session-context/{processId}",Map.class,processId);}
        catch(HttpClientErrorException error){if(error.getRawStatusCode()==404)return java.util.Collections.emptyMap();throw error;}
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean isNodeActive(String processId, String activityId) {
        Map<String, Object> result = http.getForObject(
                baseUrl + "/internal/session-context/{processId}/node/{activityId}/active",
                Map.class, processId, activityId);
        if (result == null || !(result.get("active") instanceof Boolean)) {
            throw new IllegalStateException("业务会话节点状态缺少 active");
        }
        return Boolean.TRUE.equals(result.get("active"));
    }

    @Override
    public void claimSession(String processId, String agentId, String peerId) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("agentId", agentId);
        body.put("peerId", peerId == null ? "" : peerId);
        try {
            http.postForObject(baseUrl + "/internal/reception/{processId}/accept", json(body), Void.class, processId);
        } catch (HttpClientErrorException error) {
            // 流程已离开人工节点或已被他人认领：把下游的 4xx 原样抛给调用方转成 409，
            // 不与"流程不存在"这类 404 混为一谈。
            throw error;
        }
    }

    /** 显式声明 JSON 请求体：Map 直接作为 body 时默认按表单编码，中文会乱码且嵌套结构丢失。 */
    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(body, headers);
    }
}
