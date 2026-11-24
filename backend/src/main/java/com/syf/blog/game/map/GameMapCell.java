package com.syf.blog.game.map;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 关卡模板中的格子坐标，row 是行，column 是列；两者都从 0 开始。 */
@Getter
@AllArgsConstructor
public class GameMapCell {
    private final int row;
    private final int column;
}
