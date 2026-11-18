package com.example.bank.workflow.port;

import com.example.bank.workflow.contract.QueueRequest;
import com.example.bank.workflow.contract.QueueTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** {@link RoutingPort} 的 HTTP 实现：调用路由服务的内部接口。 */
@Component
public class HttpRoutingAdapter implements RoutingPort {

    private static final Logger log = LoggerFactory.getLogger(HttpRoutingAdapter.class);

    private final RestTemplate http;
    private final String baseUrl;

    public HttpRoutingAdapter(RestTemplate http, @Value("${workflow.business-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public QueueTicket route(QueueRequest request) {
        // 不捕获下游异常：排队失败必须让流程事务回滚，理由见 RoutingPort#route 的注释
        QueueTicket ticket = http.postForObject(baseUrl + "/internal/reception/route", json(request), QueueTicket.class);
        if (ticket == null) {
            // 对端返回 2xx 但响应体为空：同样不能让流程带着"已排队"的假象继续推进
            throw new IllegalStateException("路由服务未返回工单，processId=" + request.processId);
        }
        return ticket;
    }

    @Override
    public void recordSuccess(String agentId) {
        if (agentId == null || agentId.trim().isEmpty()) return;
        try {
            http.postForObject(baseUrl + "/internal/reception/agent/{agentId}/success", null, Void.class, agentId);
        } catch (RuntimeException error) {
            // 统计数字丢失不影响已完成的交易，下一个流程会重新计数
            log.warn("登记坐席交易成功数失败，agentId={}", agentId, error);
        }
    }

    /**
     * 显式声明 JSON 请求体。
     *
     * <p>对象直接作为 body 时 RestTemplate 会用 Jackson 按 JSON 发送，但这里仍显式设置头，
     * 与 media-svc 的写法保持一致：契约里 {@code skillGroup} 等字段一旦因编码方式变化丢失，
     * 表现为技能组静默退回 GENERAL，不报错，只在坐席端表现为"派错人"。</p>
     */
    private HttpEntity<QueueRequest> json(QueueRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<QueueRequest>(body, headers);
    }
}
