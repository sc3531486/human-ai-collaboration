package com.example.bank.business.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把下游服务的失败翻回本地响应。
 *
 * <p>本服务是对外唯一入口，下游的 4xx 是<b>有业务含义的拒绝</b>：
 * 「当前不在收款确认节点，或已提交过」、「业务已被他人接听」—— 这些判断只有下游做得了，
 * 而它们是页面要显示给坐席看的提示。适配器不逐条翻译，统一在这里按状态码 + 下游 message 转出，
 * 免得每个端口各自造一套错误文案。</p>
 *
 * <p>下游 message 取自查询响应体而非 {@code getResponseBodyAsString()}：后者按 Content-Type 的
 * charset 解码，而 Spring 默认错误响应是 {@code application/json}（不带 charset），
 * 会按 ISO-8859-1 解出乱码。</p>
 *
 * <p>与 {@link WorkbenchExceptionHandler} 不冲突：那个只处理 {@code ResponseStatusException}
 * 且限定在 {@code WorkbenchController} 上，与这里处理的 {@code RestClientResponseException} 没有交集。</p>
 */
@RestControllerAdvice
public class DownstreamExceptionHandler {

    private final ObjectMapper json;

    public DownstreamExceptionHandler(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 下游返回了 4xx / 5xx。
     *
     * <p>状态码原样透出，不降级成 500：调用方（页面与联调脚本）靠状态码区分
     * 「业务拒绝」与「服务故障」。5xx 的下游原文也一并透出 —— 联调阶段它是最快的定位线索；
     * 接入生产时应收紧为只透传 4xx，5xx 换成固定文案，避免内部异常细节外泄。</p>
     */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> downstream(RestClientResponseException error) {
        String message = downstreamMessage(error);
        return ResponseEntity.status(error.getRawStatusCode()).body(body(message));
    }

    /**
     * 下游不可达（连接被拒、读超时）。
     *
     * <p>转成 503 而不是 500：这是「依赖暂时不可用」，与「本服务处理出错」是两回事，
     * 页面对前者的正确反应是提示稍后重试，而不是当成缺陷上报。</p>
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> unreachable(ResourceAccessException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body("依赖的服务暂时不可达，请稍后重试"));
    }

    /** 取下游错误体的 {@code message} 字段；取不到就退回状态码的标准英文描述。 */
    private String downstreamMessage(RestClientResponseException error) {
        byte[] raw = error.getResponseBodyAsByteArray();
        if (raw != null && raw.length > 0) {
            try {
                Map<?, ?> parsed = json.readValue(new String(raw, StandardCharsets.UTF_8), Map.class);
                Object message = parsed.get("message");
                if (message != null && !String.valueOf(message).trim().isEmpty()) return String.valueOf(message);
            } catch (Exception ignored) {
                // 下游错误体不是 JSON（例如网关返回 HTML），退回下面的标准描述
            }
        }
        return error.getStatusText();
    }

    private Map<String, Object> body(String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
