package com.example.bank.signaling.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@link PresenceEventPort} 的 HTTP 实现：把通用连接事实 POST 到
 * business 的产品事件入口 {@code /internal/events/signaling}。
 *
 * <p><b>只上报事实，不携带银行业务语义</b>：事件类型是 {@code CONNECTION_CLOSED}，
 * 载荷是 {@code subjectId/role/channel/reason/source/at}；坐席离线、工单收口等业务含义
 * 由 business 翻译。这样信令产品可以独立部署，换个消费方也不用改。</p>
 *
 * <p><b>失败按规格只记日志、不重试、不抛出</b>（这类事件自带恢复语义：下一个扫描周期
 * 会重新观察到同一事实并再次上报）。返回值是恢复路径的开关：只有 {@code true} 才允许
 * 调用方标记"已上报"。</p>
 */
@Component
public class HttpPresenceEventAdapter implements PresenceEventPort {

    private static final Logger log = LoggerFactory.getLogger(HttpPresenceEventAdapter.class);

    private static final String ENDPOINT = "/internal/events/signaling";

    private final RestTemplate rest;
    private final String businessBaseUrl;

    public HttpPresenceEventAdapter(@Qualifier("presenceEventRestTemplate") RestTemplate rest,
                                    @Value("${presence.business-base-url:http://localhost:8080}") String businessBaseUrl) {
        if (businessBaseUrl == null || businessBaseUrl.trim().isEmpty()) {
            // 启动期强校验：缺地址时不带病运行，否则每次上报都会打到非法 URL 上再被吞成"上报失败"
            throw new IllegalStateException("presence.business-base-url 未配置");
        }
        this.rest = rest;
        this.businessBaseUrl = businessBaseUrl;
    }

    @Override
    public boolean disconnected(String agentId, String reason, String source, String channel) {
        long at = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("subjectId", agentId);
        payload.put("role", "AGENT");
        payload.put("channel", channel);
        payload.put("reason", reason);
        payload.put("source", source);
        payload.put("at", at);

        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("type", "CONNECTION_CLOSED");
        event.put("occurredAt", at);
        event.put("payload", payload);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            rest.postForEntity(endpoint(), new HttpEntity<Map<String, Object>>(event, headers), Map.class);
            return true;
        } catch (RestClientException error) {
            // 规格：只记日志、不重试、不抛出。级别为 ERROR —— 这是一条已确认丢失的投递，必须能触发告警。
            log.error("连接关闭事件上报失败 subjectId={} reason={} endpoint={} error={}；该事实保持未上报，"
                    + "下一轮扫描会重新观察并再次上报", agentId, reason, endpoint(), error.toString(), error);
            return false;
        }
    }

    private String endpoint() {
        String base = businessBaseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + ENDPOINT;
    }
}
