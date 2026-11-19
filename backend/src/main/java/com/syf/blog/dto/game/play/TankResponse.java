package com.syf.blog.dto.game.play;

import com.syf.blog.game.runtime.TankState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 构造参数按字段顺序，位置与朝向均来自服务端。 */
@Getter
@AllArgsConstructor
@Schema(description = "服务端坦克状态")
public class TankResponse {
    private final Long userId;
    private final double x;
    private final double y;
    private final double size;
    private final int direction;
    private final long lastInputSeq;
    private final int hp;

    @Schema(description = "保护结束的服务端 tick；当前 tick 小于此值时处于保护中，0 表示无保护")
    private final long shieldUntilTick;

    public static TankResponse from(TankState tank) {
        TankResponse response = new TankResponse(tank.getUserId(), tank.getX(), tank.getY(),
                tank.getSize(), tank.getDirection(), tank.getLastInputSeq(), tank.getHp(),
                tank.getShieldUntilTick());
        return response;
    }
}
