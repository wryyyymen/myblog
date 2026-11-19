package com.syf.blog.dto.game.play;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "本局服务端移动规则，速度单位为像素每秒")
public class GameRulesResponse {
    private final double moveSpeed;
    private final long tickMillis;
    private final long inputTimeoutMillis;
    private final int initialHp;
    private final double bulletSpeed;
    private final long fireCooldownMillis;
    private final long matchDurationMillis;
}
