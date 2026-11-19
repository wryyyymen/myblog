package com.syf.blog.dto.game.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 退出指定的当前房间，不能凭该请求移除其他用户。 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "主动退出当前房间")
public class LeaveRoomRequest {

    @NotBlank(message = "房间号不能为空")
    @Size(max = 36, message = "房间号最多允许36个字符")
    @Schema(description = "要退出的当前房间号")
    private String roomId;
}
