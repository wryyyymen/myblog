package com.syf.blog.dto.game.play;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "玩家方向输入，不接受客户端坐标、速度或碰撞结果")
public class PlayerInputRequest {
    @NotBlank
    @Size(max = 36)
    @Schema(description = "当前房间号")
    private String roomId;

    @NotBlank
    @Size(max = 36)
    @Schema(description = "当前对局号")
    private String matchId;

    @Positive
    @Schema(description = "本局该用户递增的输入序号，刷新时从服务端已接受序号继续")
    private long seq;

    @Min(0)
    @Max(3)
    @Schema(description = "0上、1下、2左、3右；null 表示停止")
    private Integer direction;

    @Schema(description = "是否持续按住开火键，默认 false；冷却和子弹由服务端计算")
    private boolean fireHeld;
}
