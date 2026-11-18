package com.example.bank.media.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * BusinessEventPort 的 HTTP 实现：media-svc → business-svc。
 *
 * <p><b>失败一律上抛</b>：不吞异常、不静默跳过空 subject —— 回调失败却被当成成功，
 * 会让业务侧永远等不到这条媒体事件，且日志之外无从追查。注意：本端口当前在
 * media-svc 内没有任何调用方（见交付报告"待裁定项"，建议整体删除）。</p>
 */
@Component
public class HttpBusinessEventAdapter implements BusinessEventPort {

    private final RestTemplate http;
    private final String businessBaseUrl;

    public HttpBusinessEventAdapter(RestTemplate http,
                                    @Value("${media.business-base-url}") String businessBaseUrl) {
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
        http.postForObject(businessBaseUrl + path, json(event), Void.class, subject);
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> event) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(event, headers);
    }
}
