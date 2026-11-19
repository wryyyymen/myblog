package com.syf.blog.websocket.game;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** 将真实传输连接留在服务端属性中，供入站权限拒绝时立即关闭连接。 */
public class GameSocketTransportDecorator extends WebSocketHandlerDecorator {
    public static final String TRANSPORT_ATTRIBUTE = "gameSocketTransport";

    // 父类要求通过 super(handler) 安装被包装的处理器，不能用仅赋值构造替代。
    public GameSocketTransportDecorator(WebSocketHandler handler) {
        super(handler);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.getAttributes().put(TRANSPORT_ATTRIBUTE, session);
        super.afterConnectionEstablished(session);
    }
}
