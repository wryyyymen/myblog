package com.syf.blog.test;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 注册 WebSocket 地址，并启用学习示例中的定时推送。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableScheduling
public class MyWsConfig implements WebSocketConfigurer {
    private final MyWsHandler myWsHandler;
    private final MyWsInterceptor myWsInterceptor;

    /**
     * Spring 创建配置对象时，通过构造方法传入已经管理的处理器和拦截器。
     *
     * @param myWsHandler 处理连接建立、文本消息、连接关闭等事件的对象
     * @param myWsInterceptor 参与 HTTP 握手过程的拦截器对象
     */
    public MyWsConfig(MyWsHandler myWsHandler, MyWsInterceptor myWsInterceptor) {
        this.myWsHandler = myWsHandler;
        this.myWsInterceptor = myWsInterceptor;
    }

    /**
     * 应用启动时由 Spring 调用，登记 WebSocket 路径及其处理规则。
     *
     * @param registry Spring 传入的注册工具，用于绑定路径、处理器和握手拦截器
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 浏览器连接 ws://localhost:8081/myWs；保留默认的同源检查。
        // addHandler 的两个参数分别是处理器对象和连接路径。
        // 返回的注册配置对象继续调用 addInterceptors，给这个路径添加握手拦截器。
        registry.addHandler(myWsHandler, "/myWs")
                .addInterceptors(myWsInterceptor);
    }
}
