package com.syf.blog.dto.game.play;

import com.syf.blog.game.runtime.TankState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "AI 坦克；id 为正数，子弹 ownerUserId 为对应负数")
public class EnemyResponse {
    private final long id;
    private final double x;
    private final double y;
    private final double size;
    private final int direction;
    private final int hp;
    private final int maxHp;

    public static EnemyResponse from(TankState enemy) {
        EnemyResponse response = new EnemyResponse(-enemy.getUserId(), enemy.getX(), enemy.getY(),
                enemy.getSize(), enemy.getDirection(), enemy.getHp(), 1);
        return response;
    }
}
