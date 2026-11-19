package com.syf.blog.dto.game.play;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "当前成员确认本局地图与素材加载完成")
public class GameLoadedRequest {
    @NotBlank
    @Size(max = 36)
    @Schema(description = "当前房间号")
    private String roomId;

    @NotBlank
    @Size(max = 36)
    @Schema(description = "本局对局号，旧对局确认不能启动新局")
    private String matchId;
}
