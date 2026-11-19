package com.syf.blog.websocket.game;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import java.security.Principal;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 检查客户端 STOMP 消息的发送和订阅目标，避免绕过 Controller 直接向代理推送消息。
 * 由 GameWebSocketConfig 显式创建并注册，不负责重新读取 HTTP Session。
 */
public class GameMessageChannelInterceptor implements ChannelInterceptor {

    /**
     * 消息进入 Controller 或内存代理前，由客户端入站通道调用。
     * 身份来自握手阶段绑定的 Principal，不读取客户端自行声明的用户 ID。
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();

        // 心跳没有 STOMP command，需要根据 Spring 标记的消息类型单独识别。
        if (accessor.isHeartbeat()) {
            return message;
        }

        // 只有明确属于客户端的控制指令可以跳过业务目标检查。
        // 连接的登录检查仍由既有握手拦截器执行。
        boolean allowedControlCommand = isAllowedControlCommand(command);
        if (allowedControlCommand) {
            return message;
        }

        // MESSAGE 属于服务端指令，却与 SEND 共用 Spring 的 MESSAGE 消息类型。
        // 不能把其余指令全部放行，否则客户端可能绕过发送目标检查直接广播。
        if (command != StompCommand.SEND && command != StompCommand.SUBSCRIBE) {
            return reject(accessor, "客户端不允许发送该 STOMP 指令");
        }

        WebSocketSession transport = findTransport(accessor);
        if (transport != null && !transport.isOpen()) {
            return null;
        }

        Principal principal = accessor.getUser();
        if (principal == null) {
            // 不把原始消息放进异常，避免异常输出中包含会话标识等消息头。
            return reject(accessor, "游戏消息需要有效的登录身份");
        }

        String destination = accessor.getDestination();

        if (command == StompCommand.SEND) {
            boolean allowed = isAllowedSendDestination(destination);
            if (!allowed) {
                return reject(accessor, "不允许向该目标发送游戏消息");
            }
        } else {
            // 客户端只能订阅自己的用户回复，由 Spring 根据可信身份解析真实目标。
            if (!"/user/queue/replies".equals(destination)) {
                return reject(accessor, "不允许订阅该游戏消息目标");
            }
        }

        return message;
    }

    private WebSocketSession findTransport(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) return null;
        Object value = attributes.get(GameSocketTransportDecorator.TRANSPORT_ATTRIBUTE);
        if (value instanceof WebSocketSession) return (WebSocketSession) value;
        return null;
    }

    private Message<?> reject(StompHeaderAccessor accessor, String reason) {
        WebSocketSession transport = findTransport(accessor);
        if (transport == null) {
            // 独立通道调用/单元测试没有网络连接，仍以不包含消息头的异常拒绝。
            throw new MessageDeliveryException(reason);
        }
        try {
            if (transport.isOpen()) transport.close(CloseStatus.POLICY_VIOLATION.withReason("FORBIDDEN"));
        } catch (IOException exception) {
            System.out.println("[GameMessageChannelInterceptor.reject] 关闭被拒绝的游戏连接失败");
        }
        // 排序通道可能吞掉拦截器异常，因此真实连接明确关闭并停止本条消息。
        return null;
    }

    /**
     * 明确列出客户端控制指令；通过本权限检查不代表内存代理支持事务或确认机制。
     */
    private boolean isAllowedControlCommand(StompCommand command) {
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            return true;
        }

        if (command == StompCommand.DISCONNECT || command == StompCommand.UNSUBSCRIBE) {
            return true;
        }

        if (command == StompCommand.ACK || command == StompCommand.NACK) {
            return true;
        }

        if (command == StompCommand.BEGIN || command == StompCommand.COMMIT || command == StompCommand.ABORT) {
            return true;
        }

        return false;
    }

    /**
     * 精确匹配当前支持的业务入口；新增指令时，需要同时明确其消息权限。
     */
    private boolean isAllowedSendDestination(String destination) {
        if ("/app/ping".equals(destination)) {
            return true;
        }

        if ("/app/room/create".equals(destination) || "/app/room/create-coop".equals(destination)) {
            return true;
        }

        if ("/app/room/current".equals(destination) || "/app/room/list".equals(destination)) {
            return true;
        }

        if ("/app/room/join".equals(destination)) {
            return true;
        }

        if ("/app/player/ready".equals(destination)) {
            return true;
        }

        if ("/app/room/leave".equals(destination)) {
            return true;
        }

        if ("/app/room/start".equals(destination) || "/app/game/loaded".equals(destination)) {
            return true;
        }

        if ("/app/player/input".equals(destination) || "/app/game/sync".equals(destination)) {
            return true;
        }

        if ("/app/room/rematch".equals(destination)) {
            return true;
        }

        return false;
    }
}
