package com.syf.blog.dto.game.room;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 大厅中的可加入房间摘要；Lombok 全参数构造方法按以下字段顺序生成。 */
@Getter
@AllArgsConstructor
@Schema(description = "处于等待阶段、房主在线且未满员的房间摘要")
public class RoomListResponse {

    @Schema(description = "房间唯一编号，加入房间时使用")
    private final String roomId;

    @Schema(description = "房主用户 ID", example = "14")
    private final Long ownerUserId;

    @Schema(description = "当前成员数量，房主也占一个位置", example = "1")
    private final int memberCount;

    @Schema(description = "房间人数上限", example = "2")
    private final int maxMembers;

    @Schema(description = "COOP 双人合作、PVP 双人对战", example = "COOP")
    private final String mode;

    @Schema(description = "本次摘要对应的房间版本", example = "1")
    private final long version;
}
