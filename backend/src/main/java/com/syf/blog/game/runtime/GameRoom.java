package com.syf.blog.game.runtime;

import lombok.Getter;

/**
 * 后端内存中保存的游戏房间。
 */
@Getter
public class GameRoom {

    //房间的唯一编号
    private final String roomId;

    //房主的用户ID
    private final Long ownerUserId;

    /**
     * 创建房间
     *
     * @param roomId 房间编号
     * @param ownerUserId 房主的用户 ID
     * */
    public GameRoom(String roomId,Long ownerUserId){
        this.roomId = roomId;
        this.ownerUserId = ownerUserId;

        System.out.println( "[GameRoom 构造方法] roomId = " + roomId
                + "，ownerUserId = " + ownerUserId);
    }

}
