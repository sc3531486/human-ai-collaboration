package com.example.bank.workflow.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/** BusinessEventPort 的 HTTP 实现：workflow-svc → business-svc。 */
@Component
public class HttpBusinessEventAdapter implements BusinessEventPort {

    private final RestTemplate http;
    private final String businessBaseUrl;

    public HttpBusinessEventAdapter(RestTemplate http,
                                    @Value("${workflow.business-base-url}") String businessBaseUrl) {
        this.http = http;
        this.businessBaseUrl = businessBaseUrl;
    }

    @Override
    public void publishAgent(String agentId, Map<String, Object> event) {
        publish("/internal/notifications/agent/{subject}", agentId, event);
    }

    @Override
    public void publishCustomer(String customerId, Map<String, Object> event) {
        publish("/internal/notifications/customer/{subject}", customerId, event);
    }

    private void publish(String path, String subject, Map<String, Object> event) {
        // 主体缺失即不投递（判空约定见 NodeEventService#publish）；投递失败如实抛出：
        // 业务事件是给 business-svc 记账的领域通知，吞掉失败会留下"通知从未发生且无人知晓"的静默缺口。
        if (subject == null || subject.trim().isEmpty()) return;
        http.postForObject(businessBaseUrl + path, json(event), Void.class, subject);
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> event) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(event, headers);
    }
}
