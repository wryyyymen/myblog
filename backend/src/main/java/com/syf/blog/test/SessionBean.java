package com.syf.blog.test;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.web.socket.WebSocketSession;

/**
 * 保存一条连接及其演示编号。
 * Lombok 的 {@code @AllArgsConstructor} 按字段顺序生成构造方法，参数为连接对象、连接编号；
 * {@code @Getter} 生成 getWebSocketSession() 和 getClientId()，用于读取这两个字段。
 * clientId 是学习示例的连接编号，不是登录用户 ID。
 */
@AllArgsConstructor
@Getter
public class SessionBean {
    /** 经过并发发送保护包装的连接，发送消息时从这里取出。 */
    private final WebSocketSession webSocketSession;
    /** 由 AtomicInteger 递增分配的演示编号，用于日志和欢迎消息。 */
    private final int clientId;
}
