package com.syf.blog.dto.game.room;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 返回给前端的房间信息
 * */
@Getter
@Schema(description = "游戏房间信息")
public class RoomResponse {

    @Schema(description = "房间唯一编号")
    private final String roomId;

    @Schema(description = "房主的用户 ID",example = "12")
    private final Long ownerUserId;

    public RoomResponse(String roomId, Long ownerUserId) {
        this.roomId = roomId;
        this.ownerUserId = ownerUserId;

    }
}
