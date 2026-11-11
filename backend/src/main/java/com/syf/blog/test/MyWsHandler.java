package com.syf.blog.test;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 处理文本消息和连接生命周期；这里只演示通信，不处理博客业务。 */
@Component
@Slf4j
public class MyWsHandler extends TextWebSocketHandler {
    // Handler 是 Spring 单例；多个连接和定时任务会同时访问这个集合。
    private final ConcurrentMap<String, SessionBean> sessionBeanMap = new ConcurrentHashMap<>();
    // 创建一个线程安全的整数计数器，用来给每个 WebSocket 客户端分配一个唯一的递增编号。
    private final AtomicInteger clientIdMaker = new AtomicInteger();

    /**
     * 每建立一条新的 WebSocket 连接，Spring 就调用一次。
     * 为连接添加发送保护，分配演示编号，登记连接，再向该客户端发送欢迎消息。
     *
     * @param session Spring 为刚建立的这条连接创建的会话对象，可识别、发送和关闭连接
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 收到消息时的回复和定时推送可能并发，所有发送都经过同一个包装对象。
        // 三个参数：原始连接、发送耗时限制（毫秒）、等待发送的消息缓冲容量（字节）。
        // 10_000 等于 10000；64 * 1024 等于 65536。包装对象操作的仍是同一条连接。
        WebSocketSession sendingSession = new ConcurrentWebSocketSessionDecorator(
                session, 10_000, 64 * 1024);
        // 构造参数按 SessionBean 字段顺序传入：连接对象、演示编号。
        // incrementAndGet() 先加一再返回，初始值为 0，所以第一次返回 1。
        SessionBean client = new SessionBean(sendingSession, clientIdMaker.incrementAndGet());
        // Map 的 key 是 Spring 提供的连接 ID，value 是包含连接和演示编号的 client。
        sessionBeanMap.put(session.getId(), client);
        // 三个 {} 按顺序填入连接 ID、演示编号和当前登记的连接数量。
        log.info("WebSocket connected: sessionId={}, clientId={}, online={}",
                session.getId(), client.getClientId(), getConnectionCount());
        // 两个参数：消息发送目标、发送的文本；+ 将编号拼接到字符串后面。
        sendMessage(client, "welcome: clientId=" + client.getClientId());
    }

    /**
     * 客户端通过 WebSocket 发来文本消息时，由 Spring 调用。
     * 读取消息正文，加上 echo 前缀，再回复给发送它的客户端。
     *
     * @param session 发来这条消息的连接，getId() 可用于查找登记的 client
     * @param message 收到的文本消息，getPayload() 返回消息正文字符串
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 取出保存的 client，让回复也经过其中的并发发送包装对象。
        SessionBean client = sessionBeanMap.get(session.getId());
        if (client != null) {
            // 只回复发送这条消息的连接，不广播给其他连接。
            sendMessage(client, "echo: " + message.getPayload());
        }
    }

    /**
     * Spring 得知连接已关闭时调用，移除连接记录并输出日志。
     *
     * @param session 已关闭的连接
     * @param status 关闭状态，包含关闭码和可选的原因，例如 1000 表示正常关闭
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionBeanMap.remove(session.getId());
        log.info("WebSocket closed: sessionId={}, status={}, online={}",
                session.getId(), status, getConnectionCount());
    }

    /**
     * Spring 检测到连接传输异常时调用，移除记录并尝试关闭连接。
     *
     * @param session 发生传输异常的连接
     * @param exception 框架传入的异常对象，用于记录发生错误的原因
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        SessionBean client = sessionBeanMap.remove(session.getId());
        log.warn("WebSocket transport error: sessionId={}", session.getId(), exception);
        // 有登记记录时使用保存的包装对象，否则使用回调传入的连接对象。
        closeSession(client == null ? session : client.getWebSocketSession());
    }

    /**
     * 向当前登记的所有连接发送同一条文本消息，由定时推送任务调用。
     * 每条连接分别调用 sendMessage，其中一条发送失败时其他连接仍能继续接收。
     *
     * @param message 要广播的文本内容，例如服务端当前时间
     */
    void broadcast(String message) {
        for (SessionBean client : sessionBeanMap.values()) {
            sendMessage(client, message);
        }
    }

    /**
     * 读取当前连接登记表中的记录数量，供日志和测试使用。
     *
     * @return 调用 size() 时，Map 中登记的连接数量
     */
    int getConnectionCount() {
        return sessionBeanMap.size();
    }

    /**
     * 向指定客户端发送一条文本消息，供欢迎消息、echo 回复和广播共同使用。
     * 发送前检查连接状态；连接已关闭或发送失败时，清理对应登记记录。
     *
     * @param client 接收消息的目标对象，其中保存着经过发送保护包装的 WebSocketSession
     * @param message 要发送的文本内容，例如 "welcome: clientId=1"
     */
    private void sendMessage(SessionBean client, String message) {
        // 通过 Lombok 生成的 getter，取出创建 SessionBean 时保存的连接。
        WebSocketSession session = client.getWebSocketSession();
        // isOpen() 返回是否打开；! 表示取反，所以关闭的连接会进入此分支。
        if (!session.isOpen()) {
            // 两个参数分别是 key 和期望的 value，仅当两者都匹配时才删除记录。
            sessionBeanMap.remove(session.getId(), client);
            // 结束本次方法调用。方法返回类型为 void，所以 return 后面没有返回值。
            return;
        }
        try {
            // TextMessage 把字符串包装成文本消息，WebSocketSession 负责发送到客户端。
            session.sendMessage(new TextMessage(message));
        } catch (IOException | IllegalStateException | SessionLimitExceededException exception) {
            // 分别处理网络读写错误、连接状态变化，以及发送耗时或缓冲超过限制。
            // 一个连接发送失败时清理该连接，让其他在线连接继续接收推送。
            sessionBeanMap.remove(session.getId(), client);
            log.warn("WebSocket send failed: sessionId={}", session.getId(), exception);
            closeSession(session);
        }
    }

    /**
     * 发送或传输发生错误后，尝试以服务端错误状态关闭这条连接。
     * 关闭过程中出现的网络或状态异常会记录到调试日志。
     *
     * @param session 需要关闭的连接，可以是原始连接或其发送包装对象
     */
    private void closeSession(WebSocketSession session) {
        try {
            // SERVER_ERROR 对应 WebSocket 关闭码 1011，表示服务端发生错误。
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException | IllegalStateException exception) {
            log.debug("WebSocket close failed: sessionId={}", session.getId(), exception);
        }
    }
}
