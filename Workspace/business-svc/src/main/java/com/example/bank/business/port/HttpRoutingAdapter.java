package com.example.bank.business.port;

import com.example.bank.business.contract.QueueTicket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link RoutingPort} 的 HTTP 实现：转发到排队路由服务的内部接口。 */
@Component
public class HttpRoutingAdapter implements RoutingPort {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpRoutingAdapter(RestTemplate http, @Value("${business.routing-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public QueueTicket route(Map<String,Object> request){
        return http.postForObject(baseUrl+"/internal/route",json(request),QueueTicket.class);
    }

    @Override
    public QueueTicket byProcess(String processId) {
        try {
            return http.getForObject(baseUrl + "/internal/ticket/{processId}", QueueTicket.class, processId);
        } catch (HttpClientErrorException error) {
            // 404 表示这单没有工单 —— 风险校验通过、直通核心系统的业务本就不会排队。
            // 这是正常路径，与"路由服务不可达"必须区分开。
            if (error.getRawStatusCode() == 404) return null;
            throw error;
        }
    }

    @Override
    public List<QueueTicket> waiting(String skillGroup) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/queue")
                .queryParam("skillGroup", skillGroup == null ? "" : skillGroup)
                .toUriString();
        // 响应外层是 {waiting: [...], size: N}。必须用只声明 waiting 的 DTO 接，
        // 不能写成 Map<String, List<QueueTicket>> —— 同层的数字 size 会被当成 List 解，整单队列查询 500。
        // 工单本身的字段仍由 Jackson 直接映射到 QueueTicket，不手写字段表。
        ResponseEntity<QueuePage> response = http.exchange(url,
                org.springframework.http.HttpMethod.GET, null, QueuePage.class);
        QueuePage body = response.getBody();
        return body == null || body.waiting == null
                ? Collections.<QueueTicket>emptyList() : body.waiting;
    }

    /**
     * {@code /internal/queue} 的响应外壳。只声明本服务用得到的字段，其余字段由 Jackson 忽略 ——
     * routing-svc 往这个外层加字段不会打断这里，删改 {@code waiting} 才会。
     */
    static class QueuePage {
        public List<QueueTicket> waiting;
    }

    @Override
    public QueueTicket answer(String agentId, String processId, String peerId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("agentId", agentId);
        body.put("peerId", peerId == null ? "" : peerId);
        // 接听结果的 4xx（已被他人接听 / 已离开人工节点）原样上抛，由调用方转成前端提示
        return http.postForObject(baseUrl + "/internal/ticket/{processId}/answer",
                json(body), QueueTicket.class, processId);
    }

    @Override
    public QueueTicket reject(String agentId, String processId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("agentId", agentId);
        return http.postForObject(baseUrl + "/internal/ticket/{processId}/reject",
                json(body), QueueTicket.class, processId);
    }

    @Override
    public void complete(String processId) {
        http.postForObject(baseUrl + "/internal/ticket/{processId}/complete", null, Void.class, processId);
    }

    /** 显式声明 JSON 请求体：Map 直接作为 body 时默认按表单编码，中文会乱码。 */
    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(body, headers);
    }
}
