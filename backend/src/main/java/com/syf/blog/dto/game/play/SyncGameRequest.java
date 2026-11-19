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
@Schema(description = "成员请求当前房间及对局全量快照")
public class SyncGameRequest {
    @NotBlank
    @Size(max = 36)
    @Schema(description = "当前房间号")
    private String roomId;
}
