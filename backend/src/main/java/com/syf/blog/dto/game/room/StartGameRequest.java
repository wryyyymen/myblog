package com.syf.blog.dto.game.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "房主申请开始当前房间的游戏")
public class StartGameRequest {
    @NotBlank
    @Size(max = 36)
    @Schema(description = "当前房间号")
    private String roomId;
}
