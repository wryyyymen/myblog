package com.syf.blog.exception;

import org.springframework.http.HttpStatus;

/**
 * 可向客户端说明原因的业务异常，由全局处理器转换为 HTTP 响应。
 * 继承 RuntimeException，使事务中的失败操作能够回滚。
 */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;

    /**
     * 创建可向调用方说明原因的业务失败，不附带原始异常。
     *
     * @param status 对应的 HTTP 状态
     * @param message 可展示给用户的错误说明
     */
    public BusinessException(HttpStatus status, String message) {
        this(status, message, null);
    }

    /**
     * 创建保留底层原因的业务失败，供事务回滚和服务端排查使用。
     *
     * @param status 对应的 HTTP 状态
     * @param message 可展示给用户的错误说明
     * @param cause 原始异常，仅供后端排查，不返回给客户端
     */
    public BusinessException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * 读取业务失败对应的 HTTP 状态，供 HTTP 异常处理器或握手拦截器组织响应。
     *
     * @return 创建异常时指定的 HTTP 状态
     */
    public HttpStatus getStatus() {
        return status;
    }
}
