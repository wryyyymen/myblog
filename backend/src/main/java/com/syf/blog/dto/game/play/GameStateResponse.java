package com.syf.blog.dto.game.play;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
@Schema(description = "对局权威快照；tick 只在固定步长模拟后增加")
public class GameStateResponse {
    private final String roomId;
    private final String matchId;
    private final String status;
    private final long tick;
    private final long serverTime;
    private final List<TankResponse> tanks;
    private final List<BulletResponse> bullets;
    private final List<Integer> destroyedCells;
    private final Long winnerUserId;
    private final String endReason;
    private final String mode;
    private final List<EnemyResponse> enemies;
    private final BaseResponse base;
    private final int enemyTotal;
    private final int enemySpawned;
    private final int enemyDefeated;
    @Schema(description = "合作结果：VICTORY、DEFEAT、ABANDONED；运行时为 null")
    private final String teamResult;
}
