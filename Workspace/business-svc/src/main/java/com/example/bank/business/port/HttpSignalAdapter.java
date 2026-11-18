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

/** {@link SignalPort} 的 HTTP 实现：转发到消息推送服务的令牌签发接口。 */
@Component
public class HttpSignalAdapter implements SignalPort {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpSignalAdapter(RestTemplate http, @Value("${business.signaling-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> token(String agentId, String customerId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        // 两个都传给对端，由它按"坐席优先、客户次之"判定身份；本服务不复制这段判定，
        // 否则两处规则一旦分叉，同一个请求会在这里和对端得到不同的身份
        body.put("agentId", agentId == null ? "" : agentId);
        body.put("customerId", customerId == null ? "" : customerId);
        Map<String, Object> result = http.postForObject(baseUrl + "/internal/token", json(body), Map.class);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    @Override
    public void publishAgent(String agentId, Map<String, Object> event) {
        http.postForObject(baseUrl + "/internal/agent/{agentId}/publish", json(event), Map.class, agentId);
    }

    @Override
    public void publishCustomer(String customerId, Map<String, Object> event) {
        http.postForObject(baseUrl + "/internal/customer/{customerId}/publish", json(event), Map.class, customerId);
    }

    /** 显式声明 JSON 请求体：Map 直接作为 body 时默认按表单编码，中文会乱码。 */
    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(body, headers);
    }
}
