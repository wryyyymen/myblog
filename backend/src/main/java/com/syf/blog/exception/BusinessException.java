package com.syf.blog.exception;

import org.springframework.http.HttpStatus;

/**
 * 可向客户端说明原因的业务异常，由全局处理器转换为 HTTP 响应。
 * 继承 RuntimeException，使事务中的失败操作能够回滚。
 */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;

    /**
     * @param status 对应的 HTTP 状态
     * @param message 可展示给用户的错误说明
     */
    public BusinessException(HttpStatus status, String message) {
        this(status, message, null);
    }

    /**
     * @param status 对应的 HTTP 状态
     * @param message 可展示给用户的错误说明
     * @param cause 原始异常，仅供后端排查，不返回给客户端
     */
    public BusinessException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
