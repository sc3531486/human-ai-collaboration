package com.example.bank.business.application;

import com.example.bank.business.port.SignalPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 浏览器只调用业务入口；媒体用户由 media-svc 分配，令牌由 signaling-svc 签发。 */
@Service
public class MediaSessionService {
    private final RestTemplate http;
    private final SignalPort signals;
    private final String mediaUrl;
    public MediaSessionService(RestTemplate http, SignalPort signals,
            @Value("${business.media-base-url}") String mediaUrl) {
        this.http = http; this.signals = signals; this.mediaUrl = mediaUrl;
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(Map<String, String> input) {
        Map<String, Object> user = http.postForObject(mediaUrl + "/internal/media/users", input, Map.class);
        if (user == null || user.get("peerId") == null) throw new IllegalStateException("媒体服务未返回用户");
        String subject = String.valueOf(user.get("subject"));
        boolean agent = "AGENT".equals(user.get("role"));
        Map<String, Object> result = new LinkedHashMap<String, Object>(user);
        result.putAll(signals.token(agent ? subject : null, agent ? null : subject));
        return result;
    }
}
