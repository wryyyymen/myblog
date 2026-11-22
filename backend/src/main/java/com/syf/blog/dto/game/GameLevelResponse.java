package com.syf.blog.dto.game;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Lombok 生成的构造方法按字段顺序接收关卡摘要，不包含可破坏的运行时地图。 */
@Getter
@AllArgsConstructor
@Schema(description = "PVE 关卡目录")
public class GameLevelResponse {
    @Schema(description = "关卡编号", example = "1")
    private final int id;
    @Schema(description = "关卡名称")
    private final String name;
    @Schema(description = "地图特点和作战建议")
    private final String description;
    @Schema(description = "难度名称")
    private final String difficulty;
    @Schema(description = "本关敌人总数")
    private final int enemyTotal;
    @Schema(description = "最多同时存活的敌人数")
    private final int maxAliveEnemies;
    @Schema(description = "敌人速度，单位像素每秒")
    private final double enemySpeed;
    @Schema(description = "相邻出兵间隔，单位毫秒")
    private final long spawnIntervalMillis;
}
