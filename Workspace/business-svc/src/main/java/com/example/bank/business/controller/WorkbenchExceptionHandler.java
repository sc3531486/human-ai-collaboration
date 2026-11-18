package com.example.bank.business.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import org.springframework.dao.DuplicateKeyException;
import java.util.*;

/**
 * 统一错误结构供所有场景复用，不向页面暴露堆栈或数据库细节。
 *
 * <p>只剩本地唯一键冲突一类并发异常：流程引擎的乐观锁冲突随引擎迁去了流程服务，
 * 在那里表现为任务推进失败，经 {@link DownstreamExceptionHandler} 按下游状态码透出，
 * 不再从本地的异常类型判断。</p>
 */
@RestControllerAdvice(assignableTypes = { WorkbenchController.class })
public class WorkbenchExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> rejected(ResponseStatusException error) { return result(error.getStatus(), error.getReason()); }
    @ExceptionHandler({ DuplicateKeyException.class })
    public ResponseEntity<Map<String, Object>> concurrent(RuntimeException error) { return result(HttpStatus.CONFLICT, "业务已收到并发提交，请刷新状态或重试同一请求"); }
    private ResponseEntity<Map<String, Object>> result(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false); body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
