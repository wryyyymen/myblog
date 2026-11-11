package com.syf.blog.controller;

import java.security.Principal;
import com.syf.blog.dto.game.ws.GameWsResponse;
import com.syf.blog.dto.game.ws.PingRequest;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.service.game.GameRoomService;
import jakarta.validation.Valid;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;

/**
 * STOMP 消息入口，演示消息正文绑定、Bean Validation 和当前连接的定向回复。
 *
 * <p>
 * 客户端通过 /ws/tankgame 建立连接，向 /app/ping 发送 JSON 正文，
 * 并订阅 /user/queue/replies 接收回复。
 * 登录检查由端点上的握手拦截器执行。
 */
@Controller
public class GameMessageController {
        private final GameRoomService gameRoomService;

        /**
         * Spring 创建 Controller 时，传入房间业务服务。
         */
        public GameMessageController(GameRoomService gameRoomService) {
                this.gameRoomService = gameRoomService;
        }

        /**
         * 接收测试消息，打印发送者身份和消息内容。
         *
         * @param request   前端发送的消息正文，已经通过参数校验
         * @param principal 当前 WebSocket 连接绑定的用户身份,由 Spring 自动注入，不需要前端在每条消息中传递。
         * @return pong 响应，包含收到的原始文本
         */
        @MessageMapping("/ping")
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<String> ping(
                        @Valid @Payload PingRequest request,
                        Principal principal) {

                System.out.println(
                                "[GameMessageController.ping] 开始处理消息");

                // 实际会调用前面 UserPrincipal 类中的 getName()
                String userId = principal.getName();

                // 从消息对象中读取前端发送的文本
                String text = request.getText();

                System.out.println(
                                "[GameMessageController.ping] 发送者 userId = " + userId);

                System.out.println(
                                "[GameMessageController.ping] 收到 text = " + text);

                // 明确创建响应对象
                GameWsResponse<String> response = new GameWsResponse<String>("pong", text);

                System.out.println(
                                "[GameMessageController.ping] 返回 pong，payload = " + text);

                return response;
        }

        /**
         * 处理当前消息 Controller 的 JSON 转换失败和 Bean Validation 校验失败。
         *
         * <p>
         * 错误响应通过 /user/queue/replies 发给发送失败消息的连接；
         * 它是普通的 type=error 业务消息，不是 HTTP 响应或 STOMP ERROR 协议帧。
         * 握手失败与连接协议解析错误不在本方法的处理范围内。
         *
         * @return type 为 error、payload 为可向用户展示的格式或参数错误提示
         */
        @MessageExceptionHandler({
                        MethodArgumentNotValidException.class,
                        MessageConversionException.class
        })
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<String> handleInvalidMessage() {
                return new GameWsResponse<String>(
                                "error",
                                "消息格式或参数不正确，text 不能为空且最多 2000 个字符");
        }

        /**
         * 处理创建房间
         *
         * @params principal 当前连接绑定的登录身份
         * @return 房间创建的结果,止回复发送请求的连接
         * */
        @MessageMapping("/room/create")
        @SendToUser(value = "/queue/replies",broadcast = false)
        public GameWsResponse<RoomResponse> createRoom(Principal principal){
                //从连接身份读用户 ID
                String userIdText = principal.getName();
                
        }
}
