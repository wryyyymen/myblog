package com.syf.blog.dto.game.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
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

    @Min(value = 1, message = "关卡编号必须在 1 到 5 之间")
    @Max(value = 5, message = "关卡编号必须在 1 到 5 之间")
    @Schema(description = "合作 PVE 关卡编号；不传时选择第一关，PVP 仍使用原对战地图", example = "1")
    private Integer levelId;
}
