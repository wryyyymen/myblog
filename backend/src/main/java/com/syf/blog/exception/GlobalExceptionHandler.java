package com.syf.blog.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将 HTTP Controller 调用过程中抛出的业务异常转换为 HTTP 错误响应。
 *
 * <p>保留前端已使用的 {"message": "..."} 错误格式，仅输出业务异常指定的提示。
 * STOMP 消息中的转换和校验失败由消息 Controller 的消息异常处理方法处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 将业务异常转换为对应状态码及安全的错误提示。
     *
     * @param exception 登录校验或业务操作抛出的异常
     * @return 异常指定的 HTTP 状态码，以及 message 字段
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Map.of("message", exception.getMessage()));
    }
}
