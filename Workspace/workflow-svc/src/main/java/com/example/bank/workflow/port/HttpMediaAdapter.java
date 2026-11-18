package com.example.bank.workflow.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** {@link MediaPort} 的 HTTP 实现：调用多媒体服务的内部接口。 */
@Component
public class HttpMediaAdapter implements MediaPort {

    private static final Logger log = LoggerFactory.getLogger(HttpMediaAdapter.class);

    private final RestTemplate http;
    private final String baseUrl;

    public HttpMediaAdapter(RestTemplate http, @Value("${workflow.business-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public void closeRoom(String processId) {
        try {
            http.postForObject(baseUrl + "/internal/reception/{processId}/close-room", null, Void.class, processId);
        } catch (RuntimeException error) {
            // 对端对"流程本来就没有房间"是静默成功的，所以这里的异常一定是真故障（不可达、5xx）。
            // 仍然不抛出：关房是交还流程的收尾动作，失败不该回滚已完成的人工处理。
            log.warn("关闭视频房间失败，processId={}", processId, error);
        }
    }
}
