package com.syf.blog.websocket.game;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/** 命名工厂，避免用 Lambda 隐去传输包装器的创建过程。 */
public class GameSocketTransportDecoratorFactory implements WebSocketHandlerDecoratorFactory {
    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        GameSocketTransportDecorator decorator = new GameSocketTransportDecorator(handler);
        return decorator;
    }
}
