package com.syf.blog.game.map;

import com.syf.blog.dto.game.GameMapResponse;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/** 只读关卡模板；本局可破坏的砖墙保存在 GameMatch 中，不修改模板。 */
@Getter
@AllArgsConstructor
public class GameLevelDefinition {
    // Lombok 构造参数与下面字段的声明顺序相同。
    private final int id;
    private final String name;
    private final String description;
    private final String difficulty;
    private final int enemyTotal;
    private final int maxAliveEnemies;
    private final double enemySpeed;
    private final long spawnIntervalMillis;
    private final int tileSize;
    private final GameMapCell base;
    private final List<GameMapCell> playerSpawns;
    private final List<GameMapCell> enemySpawns;
    private final List<String> rows;

    /** 每次创建独立的二维列表，调用者不能破坏其他房间或下一局的地图。 */
    public GameMapResponse createMap() {
        List<List<Integer>> seed = new ArrayList<List<Integer>>();
        for (String row : rows) {
            List<Integer> cells = new ArrayList<Integer>();
            for (int column = 0; column < row.length(); column++) {
                cells.add(row.charAt(column) - '0');
            }
            seed.add(cells);
        }
        GameMapResponse map = new GameMapResponse(Long.valueOf(id), name, tileSize, seed);
        return map;
    }
}
