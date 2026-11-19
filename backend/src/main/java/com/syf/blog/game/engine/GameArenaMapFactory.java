package com.syf.blog.game.engine;

import com.syf.blog.dto.game.GameMapResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * 给过小的材质演示地图提供联机训练场，不修改数据库地图或已有 HTTP 地图接口。
 * 正常大小的数据库地图继续直接使用，非法地图仍交给移动引擎拒绝。
 */
public class GameArenaMapFactory {
    public GameMapResponse forOnlineMatch(GameMapResponse source) {
        if (!isValidSmallDemo(source)) {
            return source;
        }
        return createArena();
    }

    public GameMapResponse createArena() {
        int rows = 20;
        int columns = 30;
        List<List<Integer>> seed = new ArrayList<List<Integer>>();
        for (int row = 0; row < rows; row++) {
            List<Integer> cells = new ArrayList<Integer>();
            for (int column = 0; column < columns; column++) {
                int material = 0;
                if (row == 0 || row == rows - 1 || column == 0 || column == columns - 1) {
                    material = 2;
                }
                cells.add(material);
            }
            seed.add(cells);
        }

        // 对称的掩体留有绕行空间；四周出生区域始终可通行。
        fill(seed, 6, 6, 6, 10, 1);
        fill(seed, 13, 13, 19, 23, 1);
        fill(seed, 6, 8, 19, 19, 1);
        fill(seed, 11, 13, 10, 10, 1);
        fill(seed, 4, 4, 14, 15, 2);
        fill(seed, 15, 15, 14, 15, 2);
        fill(seed, 9, 10, 4, 6, 3);
        fill(seed, 9, 10, 23, 25, 3);
        fill(seed, 3, 4, 9, 11, 4);
        fill(seed, 15, 16, 18, 20, 4);
        fill(seed, 15, 16, 7, 9, 5);
        fill(seed, 3, 4, 20, 22, 5);
        GameMapResponse map = new GameMapResponse(1L, "双人训练场", 24, seed);
        return map;
    }

    private void fill(List<List<Integer>> seed, int firstRow, int lastRow,
                      int firstColumn, int lastColumn, int material) {
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                seed.get(row).set(column, material);
            }
        }
    }

    private boolean isValidSmallDemo(GameMapResponse map) {
        if (map == null || map.tileSize() == null || map.tileSize() < 8 || map.tileSize() > 64
                || map.seed() == null || map.seed().isEmpty() || map.seed().size() > 128
                || map.seed().get(0) == null || map.seed().get(0).isEmpty()) {
            return false;
        }
        int columns = map.seed().get(0).size();
        if (columns > 128 || map.seed().size() >= 8 && columns >= 8) {
            return false;
        }
        for (List<Integer> row : map.seed()) {
            if (row == null || row.size() != columns) {
                return false;
            }
            for (Integer material : row) {
                if (material == null || material < 0 || material > 5) {
                    return false;
                }
            }
        }
        // 至少存在相邻通行格，不能把缺少出生区域的坏地图悄悄替换成成功。
        for (int row = 0; row < map.seed().size(); row++) {
            for (int column = 0; column < columns; column++) {
                if (!isPassable(map.seed().get(row).get(column))) {
                    continue;
                }
                if (column + 1 < columns && isPassable(map.seed().get(row).get(column + 1))
                        || row + 1 < map.seed().size() && isPassable(map.seed().get(row + 1).get(column))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPassable(int material) {
        return material == 0 || material == 4 || material == 5;
    }
}
