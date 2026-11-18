package com.example.bank.routing.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/** RouteEventPort 的 HTTP 实现：routing-svc → business-svc。 */
@Component
public class HttpRouteEventAdapter implements RouteEventPort {

    private final RestTemplate http;
    private final String businessBaseUrl;

    public HttpRouteEventAdapter(RestTemplate http,
                                 @Value("${routing.business-base-url}") String businessBaseUrl) {
        this.http = http;
        this.businessBaseUrl = businessBaseUrl;
    }

    @Override
    public void publishToBusiness(String targetAgentId, Map<String, Object> event) {
        if (targetAgentId == null || targetAgentId.trim().isEmpty()) {
            // 缺少投递目标是调用方的编程错误，不再静默跳过 —— 事件丢失不留任何痕迹。
            throw new IllegalArgumentException("回调业务服务缺少 targetAgentId");
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (event != null) payload.putAll(event);
        payload.put("targetAgentId", targetAgentId);
        // 回调失败原样上抛：早先只记 log.warn 后返回成功，调用方无从得知业务侧根本没收到事件。
        http.postForObject(businessBaseUrl + "/internal/notifications/agent/{agentId}",
                json(payload), Void.class, targetAgentId);
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(body, headers);
    }
}
