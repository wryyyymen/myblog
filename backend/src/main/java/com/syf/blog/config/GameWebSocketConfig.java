package com.syf.blog.config;

import com.syf.blog.auth.GameHandshakeHandler;
import com.syf.blog.auth.GameHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

/**
 * 游戏 STOMP 端点、业务消息目标和内存消息代理的配置。
 *
 * <p>
 * 连接地址为 /ws/tankgame；/app 前缀的指令交给消息 Controller，
 * /topic 和 /queue 前缀的消息目标交给内存代理处理订阅与推送。
 */
@Configuration
@EnableWebSocketMessageBroker
public class GameWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // 校验博客登录状态，并保存可信用户信息
    private final GameHandshakeInterceptor gameHandshakeInterceptor;

    // 将可信用户 ID 转换成当前连接的 Principal
    private final GameHandshakeHandler gameHandshakeHandler;

    /**
     * Spring 创建配置对象时，自动传入这两个组件。
     *
     * @param gameHandshakeInterceptor 握手时检查登录身份
     * @param gameHandshakeHandler     将身份绑定到 WebSocket 连接
     */
    public GameWebSocketConfig(
            GameHandshakeInterceptor gameHandshakeInterceptor,
            GameHandshakeHandler gameHandshakeHandler) {

        this.gameHandshakeInterceptor = gameHandshakeInterceptor;
        this.gameHandshakeHandler = gameHandshakeHandler;
    }

    /**
     * 注册 /ws/tankgame 连接入口，保留同一连接的接收顺序，并接入握手登录检查。
     *
     * <p>
     * 这里注册的是 STOMP 的 WebSocket 端点，由 Spring 的消息处理链接管连接。
     *
     * @param registry Spring 提供的 STOMP 端点注册器
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {

        // 同一连接的消息按照收到的顺序处理
        registry.setPreserveReceiveOrder(true);

        String endpointPath = "/ws/tankgame";

        // 注册连接地址，得到这个端点的配置对象
        StompWebSocketEndpointRegistration endpointRegistration = registry.addEndpoint(endpointPath);

        // 指定握手前执行哪个拦截器
        endpointRegistration.addInterceptors(gameHandshakeInterceptor);

        // 指定握手时使用哪个 Handler 确定用户身份
        endpointRegistration.setHandshakeHandler(gameHandshakeHandler);

        System.out.println(
                "[GameWebSocketConfig.registerStompEndpoints] 已注册端点 = "
                        + endpointPath);
    }

    /**
     * 配置业务指令前缀以及内置内存消息代理。
     *
     * <p>
     * 例如发往 /app/ping 的消息会匹配 @MessageMapping("/ping")。
     * /topic、/queue 是代理目标的前缀约定，其名称本身不执行用户或房间订阅权限检查。
     *
     * @param registry Spring 提供的应用目标和消息代理配置器
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {

        // 发往 /app 开头的消息，由 @MessageMapping 方法处理
        registry.setApplicationDestinationPrefixes("/app");

        // 使用 Spring 内置的内存消息代理，管理订阅和推送
        registry.enableSimpleBroker("/topic", "/queue");
    }
}
