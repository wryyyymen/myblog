package com.syf.blog.websocket.game;

import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.dto.game.room.RoomLeftResponse;
import com.syf.blog.dto.game.play.GameEndResponse;
import com.syf.blog.dto.game.play.TankResponse;
import java.util.ArrayList;
import com.syf.blog.dto.game.ws.GameWsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将房间的最新信息发送给房间成员。
 */
@Component
@RequiredArgsConstructor
public class GameRoomNotifier {
    // Lombok 生成的构造方法接收 SimpMessagingTemplate，由 Spring 注入
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 向房间中的每个用户发送最新房间信息。
     *
     * @param roomResponse Service 返回的房间信息，包含成员名单副本
     */
    public void notifyRoomUpdated(RoomResponse roomResponse) {
        String roomId = roomResponse.getRoomId();
        List<Long> memberUserIds = roomResponse.getMemberUserIds();

        System.out.println("[GameRoomNotifier.notifyRoomUpdated] roomId = "
                        + roomId + "，通知成员 = " + memberUserIds);

        GameWsResponse<RoomResponse> notification = new GameWsResponse<RoomResponse>(
                "room:updated", roomResponse);

        // 遍历服务端确认的成员名单，逐个发送，不使用客户端传来的用户列表
        for (Long memberUserId : memberUserIds) {
            // 必须与连接身份 Principal.getName() 的返回值一致
            String userName = memberUserId.toString();
            String destination = "/queue/replies";
            System.out.println("[GameRoomNotifier.notifyRoomUpdated] 提交通知，userName = "
                            + userName + "，destination = " + destination);
            messagingTemplate.convertAndSendToUser(userName, destination, notification);
        }
    }

    /** 退出者不在剩余成员名单中，需要单独通知其所有标签页清除旧房间。 */
    public void notifyRoomLeft(Long userId, RoomLeftResponse leftResponse) {
        String userName = userId.toString();
        GameWsResponse<RoomLeftResponse> notification = new GameWsResponse<RoomLeftResponse>(
                "room:left", leftResponse);
        System.out.println("[GameRoomNotifier.notifyRoomLeft] userId = " + userId
                + "，roomId = " + leftResponse.getRoomId() + "，version = " + leftResponse.getVersion());
        messagingTemplate.convertAndSendToUser(userName, "/queue/replies", notification);
    }

    /** 游戏快照沿用用户回复目标；接收者只能来自服务端成员快照。 */
    public void notifyGameMessage(List<Long> userIds, GameWsResponse<?> message) {
        for (Long userId : userIds) {
            notifyUser(userId, message);
        }
    }

    public void notifyUser(Long userId, GameWsResponse<?> message) {
        messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/replies", message);
    }

    /** 结束时成员关系即将变化，使用最终状态里的原参与者名单发送。 */
    public void notifyGameEnded(GameEndResponse endedGame) {
        List<Long> users = new ArrayList<Long>();
        for (TankResponse tank : endedGame.getState().getTanks()) {
            users.add(tank.getUserId());
        }
        GameWsResponse<GameEndResponse> message = new GameWsResponse<GameEndResponse>("game:end", endedGame);
        notifyGameMessage(users, message);
    }
}
