package com.syf.blog.service.game;

import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.game.runtime.GameRoom;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 负责房间创建、查询和后续成员管理。
 */
@Service
public class GameRoomService {
    // 房间 ID → 房间对象
    private final Map<String,GameRoom> rooms = new HashMap<String,GameRoom>();

    //用户 ID -> 该用户所需的房间ID
    private final Map<Long,String> userRoomIds = new HashMap<Long,String>();

    /**
     * 为当前用户创建房间。
     * 如果用户已经拥有房间，则返回原来的房间。
     *
     * @param userId 从连接身份取得的可信用户 ID
     * @return 创建或查询得到的房间信息
     */
    public synchronized RoomResponse createRoom(Long userId){
        System.out.println("[GameRoomService.createRoom] 收到 userId = " + userId);

        //第一次查询；看看这个用户是否已经有房间
        String existingRoomId = userRoomIds.get(userId);

        GameRoom room;

        if(existingRoomId != null){
            // 已经有房间：通过房间 ID 取得原来的房间对象
            room = rooms.get(existingRoomId);

            System.out.println(
                    "[GameRoomService.createRoom] 复用原房间 = "
                            + existingRoomId
            );
        }else {
            //UUID 用于生成一个随机的房间号
            UUID uuid = UUID.randomUUID();
            String roomId = uuid.toString();

            // 当前创建者就是房主
            room = new GameRoom(roomId,userId);

            rooms.put(roomId,room);

            userRoomIds.put(userId,roomId);
            System.out.println(
                    "[GameRoomService.createRoom] 房间数量 = " + rooms.size()
                            + "，用户关联数量 = " + userRoomIds.size()
            );
        }
        System.out.println(
                "[GameRoomService.createRoom] 房间数量 = " + rooms.size()
                        + "，用户关联数量 = " + userRoomIds.size()
        );

        String responseRoomId = room.getRoomId();
        Long ownerUserId = room.getOwnerUserId();

        RoomResponse response = new RoomResponse(responseRoomId,ownerUserId);

        return  response;
    }

}
