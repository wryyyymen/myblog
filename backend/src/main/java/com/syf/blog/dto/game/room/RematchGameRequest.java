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
@Schema(description = "房主结束结算并返回等待，双方重新准备后开始新局")
public class RematchGameRequest {
    @NotBlank
    @Size(max = 36)
    @Schema(description = "当前结算房间号")
    private String roomId;
}
