package com.syf.blog.game.runtime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * 一辆坦克的服务端状态。Lombok 构造参数为 userId、size。
 * 身份与尺寸固定，位置、朝向与输入确认序号只由服务端修改。
 */
@Getter
@RequiredArgsConstructor
public class TankState {

    private final Long userId;

    @Setter
    private double x;

    @Setter
    private double y;

    private final double size;

    @Setter
    private int direction = 1;

    @Setter
    private long lastInputSeq;

    @Setter
    private int hp = 3;

    // 合作玩家保护结束的模拟 tick；0 表示没有保护，不使用客户端时间。
    @Setter
    private long shieldUntilTick;

    // 以服务端模拟 tick 控制开火间隔，不使用客户端时钟。
    @Setter
    private long nextFireTick;
}
