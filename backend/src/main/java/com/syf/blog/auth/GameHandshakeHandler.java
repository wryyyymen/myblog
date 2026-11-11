package com.syf.blog.auth;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * 将已经验证过的博客用户身份绑定到 WebSocket 连接。
 */
@Component
public class GameHandshakeHandler extends DefaultHandshakeHandler {

    /**
     * 在握手过程中确定当前连接的用户身份。
     *
     * @param request    当前握手请求
     * @param wsHandler  Spring 用于处理连接的 Handler
     * @param attributes 前面的握手拦截器保存的数据
     * @return 当前连接对应的用户身份
     */
    protected Principal determineUser(
            ServerHttpRequest request,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        // 读取 GameHandshakeInterceptor 从登录 Session中确认的用户 ID
        Object userIdValue = attributes.get("gameUserId");

        // 数据类型校验
        if (!(userIdValue instanceof Long)) {
            throw new HandshakeFailureException(
                    "GameHandshake获取用户ID失败");
        }

        // 检查通过后，明确转换为 Long
        Long userId = (Long) userIdValue;

        // 调用普通构造方法，创建身份对象
        UserPrincipal userPrincipal = new UserPrincipal(userId);

        // Principal 的 getName() 返回用户 ID 字符串
        return userPrincipal;
    }
}
