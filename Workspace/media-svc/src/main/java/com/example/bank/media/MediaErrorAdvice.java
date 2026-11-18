package com.example.bank.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 失败的状态码与错误码透出。
 *
 * <p>不加这层时，"流程实例不存在"、"下游服务返回了 500"、"下游服务连不上"、
 * "本服务判定前提不满足"几种情况都会糊成同一个 500，调用方无法区分是
 * <b>请求错了</b>还是<b>后端坏了</b>，排查只能靠翻日志。这里把语义原样透出，
 * 并为每类失败给出稳定的 {@code code} 供告警规则挂靠。</p>
 */
@RestControllerAdvice
public class MediaErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(MediaErrorAdvice.class);

    /** 本服务判定的显式失败：状态码与错误码原样返回（见 {@link MediaServiceException}）。 */
    @ExceptionHandler(MediaServiceException.class)
    public ResponseEntity<Map<String, Object>> mediaRejected(MediaServiceException error) {
        if (error.getStatus().is5xxServerError()) {
            log.error("media-svc 判定失败 code={} status={} message={}",
                    error.getCode(), error.getStatus().value(), error.getMessage());
        } else {
            log.warn("media-svc 判定失败 code={} status={} message={}",
                    error.getCode(), error.getStatus().value(), error.getMessage());
        }
        return ResponseEntity.status(error.getStatus()).body(body(error.getCode(), error.getMessage(), null));
    }

    /** 下游明确拒绝（流程不存在、参数不合法等）：状态码原样透出。 */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> downstreamRejected(RestClientResponseException error) {
        // Spring 5.3 的 RestClientResponseException 没有 getStatusCode()（Spring 6 才加），只能自行解析
        int raw = error.getRawStatusCode();
        HttpStatus resolved = HttpStatus.resolve(raw);
        // 5xx 是我们的下游故障，对调用方而言是"网关背后出错"，透成 502 比透成 500 更准确；
        // 无法识别的状态码也按 502 处理，避免把非标准码原样透给调用方
        HttpStatus mapped = resolved != null && resolved.is4xxClientError() ? resolved : HttpStatus.BAD_GATEWAY;
        if (mapped.is5xxServerError()) {
            log.error("下游服务返回 {}，本服务映射为 {}", raw, mapped.value());
        } else {
            log.warn("下游服务返回 {}，本服务映射为 {}", raw, mapped.value());
        }
        return ResponseEntity.status(mapped).body(body("DOWNSTREAM_REJECTED", "下游服务拒绝该请求", raw));
    }

    /** 下游连不上（未启动、超时）：是可重试的暂时性故障，用 503 表达。 */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> downstreamUnreachable(ResourceAccessException error) {
        log.error("下游服务不可达：{}", error.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body("DOWNSTREAM_UNREACHABLE", "下游服务暂时不可达", null));
    }

    private Map<String, Object> body(String code, String message, Integer downstreamStatus) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("service", "media-svc");
        result.put("code", code);
        result.put("message", message);
        if (downstreamStatus != null) result.put("downstreamStatus", downstreamStatus);
        return result;
    }
}
