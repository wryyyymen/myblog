package com.syf.blog.game.runtime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/** 基地固定坐标；Lombok 构造参数为 x、y、size，血量只由服务端修改。 */
@Getter
@RequiredArgsConstructor
public class BaseState {
    private final double x;
    private final double y;
    private final double size;
    @Setter
    private int hp = 1;
}
