package com.syf.blog.dto.game.play;

import com.syf.blog.dto.game.GameMapResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "本局固定地图、规则及初始或恢复快照")
public class GameInitResponse {
    private final String roomId;
    private final String matchId;
    private final long roomVersion;
    private final GameMapResponse map;
    private final GameRulesResponse rules;
    private final GameStateResponse state;
}
