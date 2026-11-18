package com.example.bank.business.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link ProcessPort} 的 HTTP 实现：转发到流程引擎服务的内部接口。 */
@Component
public class HttpProcessAdapter implements ProcessPort {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpProcessAdapter(RestTemplate http, @Value("${business.workflow-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public String start(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("processDefinitionKey", processDefinitionKey);
        body.put("businessKey", businessKey == null ? "" : businessKey);
        body.put("variables", variables == null ? Collections.emptyMap() : variables);
        Map<String, Object> result = post("/internal/process/start", body, Map.class);
        return result == null ? null : String.valueOf(result.get("processId"));
    }

    @Override
    public boolean isRunning(String processId) {
        Map<String, Object> result = get("/internal/process/{processId}/running", processId);
        return result != null && Boolean.TRUE.equals(result.get("running"));
    }

    @Override
    public Map<String, Object> variables(String processId) {
        return get("/internal/process/{processId}/variables", processId);
    }

    @Override
    public void setVariables(String processId, Map<String, Object> variables) {
        post("/internal/process/{processId}/variables", variables, Void.class, processId);
    }

    @Override
    public void suspend(String processId) {
        post("/internal/process/{processId}/suspend", null, Void.class, processId);
    }

    @Override
    public void activate(String processId) {
        post("/internal/process/{processId}/activate", null, Void.class, processId);
    }

    @Override
    public List<String> runningProcessIds() {
        ResponseEntity<List<String>> response = http.exchange(baseUrl + "/internal/process/running",
                HttpMethod.GET, null, new ParameterizedTypeReference<List<String>>() { });
        List<String> body = response.getBody();
        return body == null ? Collections.<String>emptyList() : body;
    }

    @Override
    public Map<String, Object> reset() {
        Map<String, Object> result = post("/internal/process/reset", null, Map.class);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    @Override
    public Map<String, Object> state(String processId) {
        return get("/internal/process/{processId}/state", processId);
    }

    @Override
    public Map<String, Object> stateOrNull(String processId) {
        try {
            return get("/internal/process/{processId}/state", processId);
        } catch (HttpClientErrorException error) {
            // 404 = 该实例从未存在。页面拿着过期标识轮询属于正常路径，与"流程服务不可达"必须区分
            if (error.getRawStatusCode() == 404) return null;
            throw error;
        }
    }

    @Override
    public Map<String, Object> trail(String processId) {
        return get("/internal/process/{processId}/trail", processId);
    }

    @Override
    public List<Map<String, Object>> scenes() {
        return getList("/internal/scenes");
    }

    @Override
    public List<Map<String, Object>> submissions(String processId) {
        return getList("/internal/process/{processId}/submissions", processId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean submit(String processId, Map<String, Object> submission) {
        // 提交内容整体透传：字段白名单来自对端的场景配置，这里逐字段取值就等于把那份配置抄了一遍
        Map<String, Object> result = post("/internal/process/{processId}/submissions",
                submission, Map.class, processId);
        return result != null && Boolean.TRUE.equals(result.get("replayed"));
    }

    /** 取一个 JSON 数组响应。用泛型引用交给 Jackson，不为每种行结构手写字段映射。 */
    private List<Map<String, Object>> getList(String path, Object... uriVariables) {
        ResponseEntity<List<Map<String, Object>>> response = http.exchange(baseUrl + path,
                HttpMethod.GET, null, new ParameterizedTypeReference<List<Map<String, Object>>>() { }, uriVariables);
        List<Map<String, Object>> body = response.getBody();
        return body == null ? Collections.<Map<String, Object>>emptyList() : body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, Object... uriVariables) {
        return http.getForObject(baseUrl + path, Map.class, uriVariables);
    }

    /**
     * 发送请求。
     *
     * <p>{@code body} 为 null 时不带请求体：Spring 的 {@code postForObject} 在 body 为 null
     * 且未指定 Content-Type 时会发出一个没有 {@code Content-Type} 的请求，
     * 对端按需 {@code @RequestBody(required=false)} 才能接住。</p>
     */
    @SuppressWarnings("unchecked")
    private <T> T post(String path, Object body, Class<T> responseType, Object... uriVariables) {
        HttpEntity<?> entity = body == null ? null : json(body);
        return http.postForObject(baseUrl + path, entity, responseType, uriVariables);
    }

    /** 显式声明 JSON 请求体：Map 直接作为 body 时默认按表单编码，中文会乱码且嵌套结构丢失。 */
    private HttpEntity<Map<String, Object>> json(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(cast(body), headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object body) {
        return body instanceof Map ? (Map<String, Object>) body : Collections.<String, Object>emptyMap();
    }
}
