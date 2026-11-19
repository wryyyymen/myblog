package com.syf.blog.websocket.game;

import com.syf.blog.game.runtime.GameBroadcast;
import com.syf.blog.service.game.GameRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * STOMP 连接生命周期入口。构造参数为 GameRoomService、GameRoomNotifier。
 * 同账号多标签共用一个房间席位，只有最后一条连接关闭才标记离线。
 */
@Component
@RequiredArgsConstructor
public class GamePresenceListener {
    private final GameRoomService gameRoomService;
    private final GameRoomNotifier gameRoomNotifier;

    // 连接标识仅在服务端保存，从不输出到日志或返回给浏览器。
    private final Map<String, Long> sessionUsers = new HashMap<String, Long>();
    private final Map<Long, Set<String>> userSessions = new HashMap<Long, Set<String>>();
    private final Set<String> closedSessionIds = new LinkedHashSet<String>();
    private static final int MAX_CLOSED_SESSION_IDS = 2_048;

    @EventListener
    public void connected(SessionConnectedEvent event) {
        Principal principal = event.getUser();
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (principal == null || sessionId == null) {
            return;
        }
        Long userId;
        try {
            userId = Long.valueOf(principal.getName());
        } catch (NumberFormatException exception) {
            return;
        }
        if (userId <= 0L) {
            return;
        }
        List<GameBroadcast> broadcasts = register(userId, sessionId);
        publish(broadcasts);
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        List<GameBroadcast> broadcasts = unregister(event.getSessionId());
        publish(broadcasts);
    }

    private synchronized List<GameBroadcast> register(Long userId, String sessionId) {
        if (closedSessionIds.contains(sessionId) || sessionUsers.containsKey(sessionId)) {
            return new ArrayList<GameBroadcast>();
        }
        Set<String> sessions = userSessions.get(userId);
        if (sessions == null) {
            sessions = new LinkedHashSet<String>();
            userSessions.put(userId, sessions);
        }
        boolean wasDisconnected = sessions.isEmpty();
        sessions.add(sessionId);
        sessionUsers.put(sessionId, userId);
        if (wasDisconnected) {
            return gameRoomService.markConnected(userId, System.currentTimeMillis());
        }
        return new ArrayList<GameBroadcast>();
    }

    private synchronized List<GameBroadcast> unregister(String sessionId) {
        // 极短连接可能先触发关闭、后收到建立通知，关闭过的连接不能再被登记为在线。
        closedSessionIds.add(sessionId);
        if (closedSessionIds.size() > MAX_CLOSED_SESSION_IDS) {
            String oldestSessionId = closedSessionIds.iterator().next();
            closedSessionIds.remove(oldestSessionId);
        }
        Long userId = sessionUsers.remove(sessionId);
        if (userId == null) {
            return new ArrayList<GameBroadcast>();
        }
        Set<String> sessions = userSessions.get(userId);
        if (sessions == null) {
            return new ArrayList<GameBroadcast>();
        }
        sessions.remove(sessionId);
        if (!sessions.isEmpty()) {
            return new ArrayList<GameBroadcast>();
        }
        userSessions.remove(userId);
        return gameRoomService.markDisconnected(userId, System.currentTimeMillis());
    }

    private void publish(List<GameBroadcast> broadcasts) {
        // 业务锁和连接登记锁均已释放，发送不阻塞后续连接回调或物理推进。
        for (GameBroadcast broadcast : broadcasts) {
            gameRoomNotifier.notifyGameMessage(broadcast.getRecipientUserIds(), broadcast.getMessage());
        }
    }
}
