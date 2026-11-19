package com.syf.blog.dto.game.room;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 返回给前端的房间信息。
 * Lombok 构造参数依次为 roomId、ownerUserId、memberUserIds、version、readyUserIds、status、matchId、connectedUserIds、mode。
 */
@Getter
@AllArgsConstructor
@Schema(description = "游戏房间信息")
public class RoomResponse {

    @Schema(description = "房间唯一编号")
    private final String roomId;

    @Schema(description = "房主的用户 ID", example = "14")
    private final Long ownerUserId;

    @Schema(description = "房间成员的用户 ID，包含房主")
    private final List<Long> memberUserIds;

    @Schema(description = "房间状态版本，从 1 开始；前端忽略较旧的同房间快照", example = "2")
    private final long version;

    @Schema(description = "已准备的成员 ID，只包含当前房间成员；成员变化时清空")
    private final List<Long> readyUserIds;

    @Schema(description = "WAITING 等待、LOADING 加载、PLAYING 运行、FINISHED 结算")
    private final String status;

    @Schema(description = "当前对局号，等待状态为 null")
    private final String matchId;

    @Schema(description = "当前仍有游戏连接的成员 ID；离线成员保留席位 15 秒")
    private final List<Long> connectedUserIds;

    @Schema(description = "PVP 双人对战，COOP 双人合作守基地")
    private final String mode;
}
