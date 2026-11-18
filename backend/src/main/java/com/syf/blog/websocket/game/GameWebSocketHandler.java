package com.syf.blog.websocket.game;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import tools.jackson.databind.json.JsonMapper;
import com.syf.blog.dto.game.ws.GameWsResponse;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.syf.blog.dto.game.ws.GameWsRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;

import java.util.Set;
import java.io.IOException;


/**
 * 保留的原生 WebSocket 学习实现，演示连接登记、JSON 解析、手动校验与响应发送。
 *
 * <p>当前 /ws/tankgame 已由 STOMP 配置注册，本类未绑定到该端点；
 * 当前游戏消息由 GameMessageController 的 @MessageMapping 方法处理。
 */
@Component
@Slf4j
public class GameWebSocketHandler extends TextWebSocketHandler {
    private final JsonMapper jsonMapper;
    private final GameConnectionRegistry gameConnectionRegistry;
    private final Validator validator;


    /**
     * 创建原生消息处理器。
     *
     * @param jsonMapper 将请求 JSON 转成对象、将响应对象转成 JSON 的工具
     * @param gameConnectionRegistry 原生方案中按用户 ID 登记当前连接的组件
     * @param validator 执行请求 DTO 上 Bean Validation 约束的校验器
     */
    public GameWebSocketHandler(JsonMapper jsonMapper, GameConnectionRegistry gameConnectionRegistry, Validator validator) {
        this.jsonMapper = jsonMapper;
        this.gameConnectionRegistry = gameConnectionRegistry;
        this.validator = validator;
    }

    /**
     * 原生连接建立后，读取握手身份，登记连接并发送 connection:ready 消息。
     *
     * <p>身份属性缺失或类型错误时，以 1008 关闭连接；
     * 校验通过后按用户 ID 登记，由登记表处理同账号旧连接替换。
     *
     * @param session Spring 为本次连接提供的会话，属性中应有 gameUserId 和 gameUsername
     * @throws Exception 关闭连接或发送欢迎消息时未在此处处理的异常
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 读取握手拦截器保存的用户信息
        Object userIdValue = session.getAttributes().get("gameUserId");
        Object usernameValue = session.getAttributes().get("gameUsername");

        // 参数类型校验，代替类型强制转化
        if(!(userIdValue instanceof Long userId)  || !(usernameValue instanceof String username)){
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // 身份检查通过，将用户与当前连接登记起来
        gameConnectionRegistry.register(userId,session);

        //创建一条连接成功的消息对象
        GameWsResponse<String> response = new GameWsResponse<String>("connection:ready","连接成功");
        sendResponse(session,response);

        log.info("游戏连接建立，用户ID：{}，用户名：{}，连接ID：{}",
                userId, username, session.getId());
    }

    /**
     * 连接关闭后，由 Spring 调用。
     *
     * <p>根据握手属性取出用户 ID，只移除与本次 session 匹配的登记记录。
     *
     * @param session 已关闭的连接
     * @param status 关闭状态，包含关闭码和原因
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status){
        Object userIdValue = session.getAttributes().get("gameUserId");
        if(userIdValue instanceof Long userId){
            gameConnectionRegistry.remove(userId,session);
            log.info("游戏连接关闭，用户ID：{}，连接ID：{}，关闭码：{}",
                    userId, session.getId(), status.getCode());
        }
    }

    /**
     * WebSocket 传输发生异常时，由 Spring 调用。
     *
     * <p>先清理登记记录，再尝试以 1011 关闭仍打开的连接；关闭时发生 IO 异常仅记录日志。
     *
     * @param session 发生异常的连接
     * @param exception 本次异常的信息
     */
    @Override
    public void handleTransportError(
            WebSocketSession session,
            Throwable exception) {

        log.warn("游戏连接发生异常，连接ID：{}",
                session.getId(), exception);

        // 1. 从登记表中移除本次出错的连接
        Object userIdValue = session.getAttributes().get("gameUserId");

        if (userIdValue instanceof Long userId) {
            gameConnectionRegistry.remove(userId, session);
        }

        // 2. 连接如果仍然打开，就主动关闭
        if (session.isOpen()) {
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException closeException) {
                log.warn("关闭异常游戏连接失败，连接ID：{}",
                        session.getId(), closeException);
            }
        }
    }

    /**
     * 处理原生方案中的 JSON 指令：转换请求、检查参数，然后确认收到的指令类型。
     *
     * <p>格式错误、空请求、字段校验失败或 payload 为 JSON null 时，
     * 回复 type=error 并结束本次处理；通过后回复 message:received，payload 为请求 type。
     * 当前实现仅验证解析与校验流程，没有执行创建或加入房间等业务操作。
     *
     * @param session 发送这条消息的连接
     * @param message 正文应为包含 type 和非空 payload 的 JSON 文本消息
     * @throws IOException 回复消息时发生的 IO 异常
     */
    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message) throws IOException {

        GameWsRequest request;

        // 1. 将 JSON 字符串转换成 Java 请求对象
        try {
            request = jsonMapper.readValue(
                    message.getPayload(),
                    GameWsRequest.class
            );
        } catch (JacksonException exception) {
            sendResponse(session,
                    new GameWsResponse<>("error", "JSON 格式或消息结构不正确"));
            return;
        }

        // JSON 文本 null 会被转换成 Java null
        if (request == null) {
            sendResponse(session,
                    new GameWsResponse<>("error", "消息必须是 JSON 对象"));
            return;
        }

        // 2. 执行请求类上的参数校验
        Set<ConstraintViolation<GameWsRequest>> errors =
                validator.validate(request);

        if (!errors.isEmpty()) {
            String reason = errors.iterator().next().getMessage();

            sendResponse(session,
                    new GameWsResponse<>("error", reason));
            return;
        }

        // JsonNode 还可能用 NullNode 对象表示 JSON 中的 null
        if (request.getPayload().isNull()) {
            sendResponse(session,
                    new GameWsResponse<>("error", "消息数据不能为空"));
            return;
        }

        // 3. 暂时回复收到的指令类型，验证解析成功
        sendResponse(session,
                new GameWsResponse<>("message:received", request.getType()));
    }

    /**
     * 将响应对象转换成 JSON，发送给指定连接。
     *
     * @param session 接收消息的客户端连接
     * @param response 要发送的消息对象
     * @param <T> payload 的数据类型
     * @throws IOException 通过当前连接发送文本消息时发生的 IO 异常
     */
    private <T> void sendResponse(WebSocketSession session,
                                  GameWsResponse<T> response) throws IOException {
        String json = jsonMapper.writeValueAsString(response);

        session.sendMessage(new TextMessage(json));
    }

}
