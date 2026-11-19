package com.syf.blog.game.runtime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 单颗子弹的中心位置。Lombok 构造参数依次为 id、ownerUserId、x、y、direction。
 * 所属玩家和方向在生成时固定，只有位置在模拟时改变。
 */
@Getter
@AllArgsConstructor
public class BulletState {
    private final long id;

    private final Long ownerUserId;

    @Setter
    private double x;

    @Setter
    private double y;

    private final int direction;
}
