package com.example.bank.media;

import org.springframework.http.HttpStatus;

/**
 * media-svc 判定的显式失败：携带 HTTP 状态码与业务错误码，经 {@link MediaErrorAdvice} 原样返回。
 *
 * <p>存在的唯一理由是<b>不允许任何失败被吞成默认成功</b>：下游响应不符合契约、
 * 房间推送前提不满足这类问题，都必须以可区分的状态码与错误码暴露给调用方，
 * 而不是退回 200、空表或占位值。调用方据 {@code code} 告警，不必解析文案。</p>
 */
public class MediaServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    public MediaServiceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public MediaServiceException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
