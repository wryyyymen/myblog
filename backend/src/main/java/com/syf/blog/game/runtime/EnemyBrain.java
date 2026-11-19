package com.syf.blog.game.runtime;

import lombok.Getter;
import lombok.Setter;

/** 单个 AI 的下一个格子中心。到达中心后才换方向，避免斜切墙角。 */
@Getter
@Setter
public class EnemyBrain {
    private Double targetX;
    private Double targetY;
    private int blockedTicks;
}
