package com.syf.blog.dto.game.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 加入房间的请求正文。
 * 对应前端发送的 {"roomId":"房间号"}。
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "加入游戏房间的请求")
public class JoinRoomRequest {

    @NotBlank(message = "房间号不能为空")
    @Size(max = 36,message = "房间号最多允许36个字符")
    @Schema(description = "想加入的房间唯一编号")
    private String roomId;
}
