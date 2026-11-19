package com.syf.blog.websocket.game;

import com.syf.blog.auth.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * 直接验证入站权限，不启动 Spring，也不连接数据库。
 */
class GameMessageChannelInterceptorTest {

    private final GameMessageChannelInterceptor interceptor = new GameMessageChannelInterceptor();
    private final MessageChannel channel = mock(MessageChannel.class);

    @Test
    void authenticatedUserCanSendAllSupportedCommands() {
        String[] destinations = {
                "/app/ping",
                "/app/room/create",
                "/app/room/create-coop",
                "/app/room/current",
                "/app/room/list",
                "/app/room/join",
                "/app/player/ready",
                "/app/room/leave",
                "/app/room/start",
                "/app/game/loaded",
                "/app/player/input",
                "/app/game/sync",
                "/app/room/rematch"
        };

        for (String destination : destinations) {
            Message<byte[]> message = authenticatedMessage(StompCommand.SEND, destination);
            Message<?> acceptedMessage = interceptor.preSend(message, channel);
            assertSame(message, acceptedMessage);
        }
    }

    @Test
    void authenticatedUserCanSubscribeToOwnReplies() {
        Message<byte[]> message = authenticatedMessage(
                StompCommand.SUBSCRIBE, "/user/queue/replies");

        Message<?> acceptedMessage = interceptor.preSend(message, channel);

        assertSame(message, acceptedMessage);
    }

    @Test
    void sendingDirectlyToUsersOrBrokerDestinationsIsRejected() {
        String[] destinations = {
                "/user/15/queue/replies",
                "/user/queue/replies",
                "/topic/game",
                "/queue/replies",
                "/queue/replies-user-other-connection"
        };

        for (String destination : destinations) {
            Message<byte[]> message = authenticatedMessage(StompCommand.SEND, destination);
            assertRejected(message, "不允许向该目标发送游戏消息");
        }
    }

    @Test
    void unknownApplicationSendDestinationIsRejected() {
        Message<byte[]> message = authenticatedMessage(StompCommand.SEND, "/app/room/unknown");

        assertRejected(message, "不允许向该目标发送游戏消息");
    }

    @Test
    void subscribingToOtherUsersOrDirectBrokerDestinationsIsRejected() {
        String[] destinations = {
                "/user/15/queue/replies",
                "/topic/game",
                "/queue/replies",
                "/queue/replies-user-other-connection",
                "/app/ping"
        };

        for (String destination : destinations) {
            Message<byte[]> message = authenticatedMessage(StompCommand.SUBSCRIBE, destination);
            assertRejected(message, "不允许订阅该游戏消息目标");
        }
    }

    @Test
    void missingOrSimilarSendDestinationDoesNotMatchWhitelist() {
        String[] destinations = { null, "", "/app/ping/", "/app/room/create/other",
                "/app/room/list/", "/app/room/list/other" };

        for (String destination : destinations) {
            Message<byte[]> message = authenticatedMessage(StompCommand.SEND, destination);
            assertRejected(message, "不允许向该目标发送游戏消息");
        }
    }

    @Test
    void missingOrSimilarSubscriptionDestinationDoesNotMatchWhitelist() {
        String[] destinations = { null, "", "/user/queue/replies/", "/user/queue/replies-extra" };

        for (String destination : destinations) {
            Message<byte[]> message = authenticatedMessage(StompCommand.SUBSCRIBE, destination);
            assertRejected(message, "不允许订阅该游戏消息目标");
        }
    }

    @Test
    void anonymousUserCannotSendOrSubscribeEvenToAllowedDestination() {
        Message<byte[]> sendMessage = message(StompCommand.SEND, "/app/ping", null);
        Message<byte[]> subscribeMessage = message(
                StompCommand.SUBSCRIBE, "/user/queue/replies", null);
        Message<byte[]> listMessage = message(StompCommand.SEND, "/app/room/list", null);

        assertRejected(sendMessage, "游戏消息需要有效的登录身份");
        assertRejected(subscribeMessage, "游戏消息需要有效的登录身份");
        assertRejected(listMessage, "游戏消息需要有效的登录身份");
    }

    @Test
    void clientControlCommandsDoNotUseBusinessDestinationWhitelist() {
        StompCommand[] commands = {
                StompCommand.CONNECT,
                StompCommand.STOMP,
                StompCommand.DISCONNECT,
                StompCommand.UNSUBSCRIBE,
                StompCommand.ACK,
                StompCommand.NACK,
                StompCommand.BEGIN,
                StompCommand.COMMIT,
                StompCommand.ABORT
        };

        for (StompCommand command : commands) {
            Message<byte[]> message = message(command, "/not-a-business-target", null);
            Message<?> acceptedMessage = interceptor.preSend(message, channel);
            assertSame(message, acceptedMessage);
        }
    }

    @Test
    void heartbeatWithoutPrincipalIsAllowed() {
        StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
        Message<byte[]> heartbeat = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> acceptedMessage = interceptor.preSend(heartbeat, channel);

        assertSame(heartbeat, acceptedMessage);
    }

    @Test
    void serverOnlyCommandsAreRejectedEvenForAuthenticatedUser() {
        StompCommand[] commands = {
                StompCommand.MESSAGE,
                StompCommand.CONNECTED,
                StompCommand.RECEIPT,
                StompCommand.ERROR
        };

        for (StompCommand command : commands) {
            Message<byte[]> message = authenticatedMessage(command, "/queue/replies");
            assertRejected(message, "客户端不允许发送该 STOMP 指令");
        }
    }

    @Test
    void messageCommandCannotReuseAllowedSendDestination() {
        // 即使目标是合法业务入口，客户端也必须使用 SEND，不能伪造服务端 MESSAGE。
        Message<byte[]> message = authenticatedMessage(StompCommand.MESSAGE, "/app/ping");

        assertRejected(message, "客户端不允许发送该 STOMP 指令");
    }

    @Test
    void missingStompCommandIsNotTreatedAsHeartbeat() {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination("/queue/replies");
        accessor.setUser(new UserPrincipal(14L));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertRejected(message, "客户端不允许发送该 STOMP 指令");
    }

    @Test
    void rejectionDoesNotIncludeSessionHeaders() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/queue/replies");
        accessor.setUser(new UserPrincipal(14L));
        accessor.setSessionId("private-test-session-id");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        MessageDeliveryException exception = assertRejected(message, "不允许向该目标发送游戏消息");

        assertNull(exception.getFailedMessage());
        assertFalse(exception.toString().contains("private-test-session-id"));
    }

    private Message<byte[]> authenticatedMessage(StompCommand command, String destination) {
        UserPrincipal principal = new UserPrincipal(14L);
        Message<byte[]> message = message(command, destination, principal);
        return message;
    }

    private Message<byte[]> message(StompCommand command, String destination, Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setUser(principal);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return message;
    }

    private MessageDeliveryException assertRejected(Message<?> message, String expectedMessage) {
        try {
            interceptor.preSend(message, channel);
        } catch (MessageDeliveryException exception) {
            assertEquals(expectedMessage, exception.getMessage());
            return exception;
        }

        fail("该消息应被入站权限检查拒绝");
        return null;
    }
}
