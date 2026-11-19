package com.syf.blog.dto.game.room;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Lombok 生成接收 roomId、version 的构造方法。 */
@Getter
@AllArgsConstructor
@Schema(description = "当前账号已退出房间的确认")
public class RoomLeftResponse {

    @Schema(description = "已退出的房间号")
    private final String roomId;

    @Schema(description = "退出操作完成后的版本，用于忽略迟到的旧通知")
    private final long version;
}
