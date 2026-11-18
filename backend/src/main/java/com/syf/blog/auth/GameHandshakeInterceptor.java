package com.syf.blog.auth;

import com.syf.blog.domain.BlogUser;
import com.syf.blog.exception.BusinessException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 游戏 WebSocket 端点的登录检查入口，复用博客现有的 HTTP Session 身份校验。
 *
 * <p>
 * 仅在建立连接的握手阶段执行；检查通过后保存服务端确认的用户 ID 和用户名，
 * 供连接身份绑定或消息处理逻辑读取。
 */
@Component
public class GameHandshakeInterceptor implements HandshakeInterceptor {

    private final CurrentUserService currentUserService;

    /**
     * 创建游戏握手拦截器。
     *
     * @param currentUserService 统一读取登录会话并检查数据库账号状态的身份服务
     */
    public GameHandshakeInterceptor(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    /**
     * 在 WebSocket 升级前检查登录，决定是否继续握手。
     *
     * <p>
     * 非 Servlet 请求设置 HTTP 400 并拒绝；登录会话无效时采用业务异常的状态码
     * （当前登录失败为 HTTP 401）并拒绝；通过后写入 gameUserId、gameUsername。
     *
     * @param request    当前握手请求，Servlet 实现用于读取浏览器已有的 HTTP Session
     * @param response   当前握手响应，用于设置拒绝请求时的 HTTP 状态
     * @param wsHandler  握手成功后处理连接的 Spring WebSocket Handler
     * @param attributes 要传给新 WebSocket 会话的属性集合，由此保存可信用户身份
     * @return true 表示允许后续握手处理，false 表示终止握手
     * @throws Exception 未在本方法转换为 HTTP 状态的其他握手处理异常
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Map<String, Object> attributes) throws Exception {

        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        try {
            // 复用博客登录校验：读取 Session，并查询账号是否有效
            BlogUser user = currentUserService.requireLogin(servletRequest.getServletRequest());

            Long userId = user.getId();

            System.out.println(
                    "[GameHandshakeInterceptor.beforeHandshake] 登录校验通过，userId = "
                            + userId);
                            
            // 保存服务端确认的身份，供连接建立后的处理器读取
            attributes.put("gameUserId", user.getId());
            attributes.put("gameUsername", user.getUsername());

            System.out.println(
                    "[GameHandshakeInterceptor.beforeHandshake] attributes 中的 gameUserId = "
                            + attributes.get("gameUserId"));

            return true;
        } catch (BusinessException exception) {
            // 登录无效时，设置对应 HTTP 状态并中止握手
            response.setStatusCode(exception.getStatus());
        }
        return false;
    }

    /**
     * Spring 在握手处理后调用的通知回调，当前实现不执行额外清理。
     *
     * @param request   本次握手请求
     * @param response  本次握手响应
     * @param wsHandler 参与握手的 WebSocket Handler
     * @param exception 握手过程中出现的异常，没有异常时为 null
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            @Nullable Exception exception) {

    }
}
