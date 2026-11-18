package com.syf.blog.websocket.game;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;



/**
 * 原生 WebSocket 示例使用的账号与连接登记表，按用户 ID 保存当前连接。
 *
 * <p>当前游戏端点已切换到 STOMP，本类由原生 GameWebSocketHandler 显式调用，
 * 不会自动接管 STOMP 的连接管理。
 */
@Component
@Slf4j
public class GameConnectionRegistry {

    // key：登录用户 ID
    // value：该用户对应的 WebSocket 连接
    private final ConcurrentMap<Long, WebSocketSession> connections = new ConcurrentHashMap<>();

    /**
     * 登记新连接，并关闭同账号原来的连接。
     *
     * <p>先保存新连接，再尝试以 1008 / REPLACED 关闭旧连接。
     * 关闭旧连接发生 IO 异常时记录日志，新连接的登记仍然保留。
     *
     * @param userId 服务端确认的用户 ID
     * @param session 新建立的连接
     */
    // 同账号建立新的原生游戏连接时，替换原先登记的连接。
    public void register(Long userId,WebSocketSession session){
        // put 保存新值，同时返回这个用户之前登记的连接
        WebSocketSession oldSession = connections.put(userId, session);
        if (oldSession != null
                && !oldSession.getId().equals(session.getId())
                && oldSession.isOpen()) {

            try {
                oldSession.close(
                        CloseStatus.POLICY_VIOLATION.withReason("REPLACED")
                );
            } catch (IOException exception) {
                log.warn("关闭旧游戏连接失败，用户ID：{}", userId, exception);
            }
        }
    }



    /**
     * 清理指定用户的连接记录。
     *
     * <p>只有登记表中的当前值与传入的 session 匹配时才删除。
     * 因此旧连接延迟到达的关闭回调不会误删后来登记的新连接；重复清理也是安全的。
     *
     * @param userId 连接所属的用户 ID
     * @param session 本次关闭的连接
     */
    public void remove(Long userId, WebSocketSession session) {
        connections.remove(userId, session);
    }
}
