package com.example.bank.business.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;

/** {@link MediaPort} 的 HTTP 实现：business-svc 统一编排媒体房间，不让 workflow-svc/routing-svc 直连 media-svc。 */
@Component
public class HttpMediaAdapter {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpMediaAdapter(RestTemplate http, @Value("${business.media-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> openRoom(String processId, Map<String, Object> context) {
        Map<String, Object> result = http.postForObject(
                baseUrl + "/internal/room/{processId}/open", json(context), Map.class, processId);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    public void inviteRoom(String processId, Map<String, Object> context) {
        http.postForObject(baseUrl + "/internal/room/{processId}/invite", json(context), Void.class, processId);
    }

    /**
     * 关闭房间。
     *
     * <p><b>失败原样上抛</b>：.rd 系统架构设计 §3.4 要求媒体命令「失败持久化重试」，
     * 而重试的前提是失败能被看见。这里早先吞掉异常只记一行日志，后果是两层调用方的
     * 处理全部变成死代码：{@code ReceptionTransitionService} 明明写了
     * {@code catch → roomClosed=false} 并把未关房的事实落进交接记录等待恢复，
     * 却永远走不到那个分支，回执上恒为 {@code roomClosed=true}。
     * 收尾动作是否可以忽略属于业务判断，判断权交给知道业务含义的调用方。</p>
     */
    public void closeRoom(String processId, Map<String,Object> context) {
        http.postForObject(baseUrl + "/internal/room/{processId}/close", json(context), Void.class, processId);
    }

    public void hangupRoom(String roomId, Map<String,Object> context) {
        http.postForObject(baseUrl + "/internal/room/{roomId}/hangup", json(context), Void.class, roomId);
    }

    public void updateParticipants(String roomId, Map<String,Object> context) {
        http.postForObject(baseUrl + "/internal/room/{roomId}/participants", json(context), Void.class, roomId);
    }

    /** 显式声明 JSON 请求体，保证中文和嵌套上下文按契约传输。 */
    private org.springframework.http.HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new org.springframework.http.HttpEntity<Map<String, Object>>(
                body == null ? Collections.<String, Object>emptyMap() : body, headers);
    }
}
