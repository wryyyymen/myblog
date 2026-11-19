package com.syf.blog.controller;

import java.security.Principal;
import java.util.List;
import com.syf.blog.dto.game.ws.GameWsResponse;
import com.syf.blog.dto.game.ws.PingRequest;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.dto.game.room.RoomListResponse;
import com.syf.blog.dto.game.room.PlayerReadyRequest;
import com.syf.blog.dto.game.room.LeaveRoomRequest;
import com.syf.blog.dto.game.room.RoomLeftResponse;
import com.syf.blog.game.runtime.RoomLeaveResult;
import com.syf.blog.service.game.GameRoomService;
import com.syf.blog.dto.game.room.JoinRoomRequest;
import com.syf.blog.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import com.syf.blog.websocket.game.GameRoomNotifier;
import lombok.RequiredArgsConstructor;

/**
 * STOMP 消息入口，演示消息正文绑定、Bean Validation 和当前连接的定向回复。
 *
 * <p>
 * 客户端通过 /ws/tankgame 建立连接，向 /app/ping 发送 JSON 正文，
 * 并订阅 /user/queue/replies 接收回复。
 * 登录检查由端点上的握手拦截器执行。
 * Lombok 生成的构造方法依次接收 GameRoomService 和 GameRoomNotifier。
 */
@Controller
@RequiredArgsConstructor
public class GameMessageController {
        // 负责创建、加入房间等业务
        private final GameRoomService gameRoomService;

        // 负责将房间信息发送给成员
        private final GameRoomNotifier gameRoomNotifier;

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
         * 处理消息正文转换失败和参数校验失败。
         */
        @MessageExceptionHandler({
                        MethodArgumentNotValidException.class,
                        MessageConversionException.class
        })
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<String> handleInvalidMessage() {
                String message = "消息格式或参数不正确，请检查必填字段和长度";

                System.out.println(
                                "[GameMessageController.handleInvalidMessage] 返回错误 = "
                                                + message);

                GameWsResponse<String> response = new GameWsResponse<String>("error", message);

                return response;
        }

        /** 查询可加入房间，只回复当前连接；查询不会为请求者创建房间。 */
        @MessageMapping("/room/list")
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<List<RoomListResponse>> listRooms(Principal principal) {
                Long userId = Long.valueOf(principal.getName());
                List<RoomListResponse> rooms = gameRoomService.listAvailableRooms(userId);
                GameWsResponse<List<RoomListResponse>> response = new GameWsResponse<List<RoomListResponse>>(
                                "room:list", rooms);
                return response;
        }

        /** 连接或重连后查询当前房间，不占用新的房间资源。 */
        @MessageMapping("/room/current")
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<RoomResponse> currentRoom(Principal principal) {
                Long userId = Long.valueOf(principal.getName());
                RoomResponse roomResponse = gameRoomService.getCurrentRoom(userId);
                GameWsResponse<RoomResponse> response = new GameWsResponse<RoomResponse>(
                                "room:current", roomResponse);
                return response;
        }

        /**
         * 处理创建房间
         *
         * @param principal 当前连接绑定的登录身份
         * @return 房间创建的结果，只回复发送请求的连接；另向所有房间成员推送最新状态
         */
        @MessageMapping("/room/create")
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<RoomResponse> createRoom(Principal principal) {
                // 从连接身份读用户 ID
                String userIdText = principal.getName();
                Long userId = Long.valueOf(userIdText);

                System.out.println(
                                "[GameMessageController.createRoom] 请求用户 ID = " + userId);

                RoomResponse roomResponse = gameRoomService.createRoom(userId);

                // 直接回复用于确认本次请求，通知用于让同房间的所有成员更新界面
                gameRoomNotifier.notifyRoomUpdated(roomResponse);

                System.out.println(
                                "[GameMessageController.createRoom] 返回房间 ID = "
                                                + roomResponse.getRoomId());

                GameWsResponse<RoomResponse> response = new GameWsResponse<RoomResponse>(
                                "room:created",
                                roomResponse);
                return response;
        }

        /** 双人合作 PVE 房间，后续沿用相同的加入、准备和开始消息。 */
        @MessageMapping("/room/create-coop")
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<RoomResponse> createCoopRoom(Principal principal) {
                Long userId = Long.valueOf(principal.getName());
                RoomResponse room = gameRoomService.createCoopRoom(userId);
                gameRoomNotifier.notifyRoomUpdated(room);
                GameWsResponse<RoomResponse> response = new GameWsResponse<RoomResponse>("room:created", room);
                return response;
        }

        /**
         * 接收加入房间的消息。
         *
         * @param request   从消息正文转换并校验后的请求对象
         * @param principal 握手时绑定到当前连接的登录身份
         * @return 加入后的房间信息，只回复发起请求的连接
         */
        @MessageMapping("/room/join")
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<RoomResponse> joinRoom(
                        @Valid @Payload JoinRoomRequest request, Principal principal) {
                System.out.println("[GameMessageController.joinRoom] 开始处理加入请求");

                // 从当前连接的可信身份中取得用户ID
                String userIdText = principal.getName();
                Long userId = Long.valueOf(userIdText);
                // 从前端发送的请求正文中取得目标房间号
                String roomId = request.getRoomId();

                System.out.println("[GameMessageController.joinRoom] userId = "
                                + userId + "，roomId = " + roomId);

                // 房间存在、成员上限等业务规则，由 Service 检查
                RoomResponse roomResponse = gameRoomService.joinRoom(userId, roomId);

                // 新成员加入后，房主和加入者都收到同一份房间快照
                gameRoomNotifier.notifyRoomUpdated(roomResponse);

                System.out.println("[GameMessageController.joinRoom] 加入成功，当前成员 = "
                                + roomResponse.getMemberUserIds());

                GameWsResponse<RoomResponse> response = new GameWsResponse<RoomResponse>(
                                "room:joined",
                                roomResponse);

                return response;
        }

        /**
         * 当前成员准备或取消准备。roomId 来自请求，用户 ID 来自可信连接身份。
         * Service 检查成员资格；成功后向成员同步准备名单。
         */
        @MessageMapping("/player/ready")
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<RoomResponse> setReady(
                        @Valid @Payload PlayerReadyRequest request, Principal principal) {
                String userIdText = principal.getName();
                Long userId = Long.valueOf(userIdText);
                String roomId = request.getRoomId();
                boolean ready = request.getReady().booleanValue();
                System.out.println("[GameMessageController.setReady] userId = " + userId
                                + "，roomId = " + roomId + "，ready = " + ready);

                RoomResponse roomResponse = gameRoomService.setReady(userId, roomId, ready);
                gameRoomNotifier.notifyRoomUpdated(roomResponse);
                GameWsResponse<RoomResponse> response = new GameWsResponse<RoomResponse>(
                                "room:ready", roomResponse);
                return response;
        }

        /**
         * 当前成员主动退出。通知同账号所有连接清除旧房间，再通知剩余成员更新。
         * 重复退出或跨房间退出由 Service 拒绝，不影响后来加入的新房间。
         */
        @MessageMapping("/room/leave")
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<RoomLeftResponse> leaveRoom(
                        @Valid @Payload LeaveRoomRequest request, Principal principal) {
                String userIdText = principal.getName();
                Long userId = Long.valueOf(userIdText);
                String roomId = request.getRoomId();
                System.out.println("[GameMessageController.leaveRoom] userId = " + userId
                                + "，roomId = " + roomId);

                RoomLeaveResult result = gameRoomService.leaveRoom(userId, roomId);
                if (result.getEndedGame() != null) {
                        gameRoomNotifier.notifyGameEnded(result.getEndedGame());
                }
                RoomLeftResponse leftResponse = result.getLeftResponse();
                gameRoomNotifier.notifyRoomLeft(userId, leftResponse);

                RoomResponse remainingRoomResponse = result.getRemainingRoomResponse();
                if (remainingRoomResponse != null) {
                        gameRoomNotifier.notifyRoomUpdated(remainingRoomResponse);
                }

                GameWsResponse<RoomLeftResponse> response = new GameWsResponse<RoomLeftResponse>(
                                "room:left", leftResponse);
                return response;
        }

        /**
         * 将房间业务失败转换为当前连接能收到的错误消息。
         *
         * @param exception Service 抛出的、可向用户展示的业务异常
         * @return type 为 error，payload 为失败原因
         */
        @MessageExceptionHandler(BusinessException.class)
        @SendToUser(value = "/queue/replies", broadcast = false)
        public GameWsResponse<String> handleBusinessException(
                        BusinessException exception) {

                String message = exception.getMessage();

                System.out.println(
                                "[GameMessageController.handleBusinessException] 返回错误 = "
                                                + message);

                GameWsResponse<String> response = new GameWsResponse<String>("error", message);

                return response;

        }
}
