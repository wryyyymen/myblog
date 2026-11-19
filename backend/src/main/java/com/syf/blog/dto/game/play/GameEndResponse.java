package com.syf.blog.dto.game.play;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "对局结算，包含胜者或平局及最终战局")
public class GameEndResponse {
    private final String roomId;
    private final String matchId;
    private final String reason;
    private final Long leavingUserId;
    private final GameStateResponse state;
    private final Long winnerUserId;
}
