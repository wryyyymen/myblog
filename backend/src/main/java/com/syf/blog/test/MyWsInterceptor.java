package com.syf.blog.test;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** 握手仍然是 HTTP 请求；这里仅观察握手过程，不读取登录 Session。 */
@Component
@Slf4j
public class MyWsInterceptor implements HandshakeInterceptor {
    /**
     * 浏览器申请建立连接时，Spring 在 HTTP 握手前调用此方法。
     * 当前实现记录客户端地址，并允许继续握手。
     *
     * @param request 本次握手的 HTTP 请求，可读取请求地址、请求头和客户端地址
     * @param response 本次握手的 HTTP 响应，可设置响应头或拒绝握手时的 HTTP 状态码
     * @param wsHandler 本次连接注册的 WebSocket 处理器，本例中为 MyWsHandler
     * @param attributes 当前握手的附加属性；放入的内容会复制到连接的 attributes 中，
     *                   连接建立后可通过 WebSocketSession.getAttributes() 读取
     * @return true 表示允许继续握手，false 表示中止本次握手
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        log.info("WebSocket handshake started: remoteAddress={}", request.getRemoteAddress());
        // true 表示允许继续握手；连接建立后才会调用 Handler 的连接回调。
        return true;
    }

    /**
     * Spring 在握手处理结束后调用，记录本次握手的处理结果。
     * 连接关闭时执行的是 Handler 的 afterConnectionClosed 方法。
     *
     * @param request 本次握手的 HTTP 请求
     * @param response 本次握手的 HTTP 响应
     * @param wsHandler 本次连接对应的 WebSocket 处理器
     * @param exception 握手过程中发生的异常；@Nullable 表示此参数可能为 null
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, @Nullable Exception exception) {
        if (exception != null) {
            // 日志最后一个参数是异常对象，日志框架会输出它的异常信息。
            log.warn("WebSocket handshake failed: remoteAddress={}", request.getRemoteAddress(), exception);
        } else {
            // 这是握手阶段结束，不是 WebSocket 连接断开。
            log.info("WebSocket handshake finished: remoteAddress={}", request.getRemoteAddress());
        }
    }
}
