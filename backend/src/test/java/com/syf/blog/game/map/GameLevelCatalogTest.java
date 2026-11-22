package com.syf.blog.game.map;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 只读取 classpath 关卡配置，不启动 Spring、不连接数据库。 */
class GameLevelCatalogTest {
    private final GameLevelCatalog catalog = GameLevelCatalog.getDefault();

    @Test
    void fiveLevelsHaveDistinctLayoutsAndValidRoutesBetweenAllSpawns() {
        assertEquals(5, catalog.getLevels().size());
        Set<List<String>> layouts = new HashSet<List<String>>();
        int previousTotal = 0;
        for (GameLevelDefinition level : catalog.getLevels()) {
            assertEquals(26, level.getRows().size());
            assertTrue(level.getEnemyTotal() > previousTotal);
            previousTotal = level.getEnemyTotal();
            layouts.add(level.getRows());

            Set<Integer> reachable = reachableCells(level);
            for (GameMapCell spawn : level.getPlayerSpawns()) {
                assertTrue(reachable.contains(spawn.getRow() * 26 + spawn.getColumn()), level.getName());
            }
            for (GameMapCell spawn : level.getEnemySpawns()) {
                assertTrue(reachable.contains(spawn.getRow() * 26 + spawn.getColumn()), level.getName());
            }
            int approach = (level.getBase().getRow() - 2) * 26 + level.getBase().getColumn();
            assertTrue(reachable.contains(approach), level.getName() + "必须能到达基地前方");
        }
        assertEquals(5, layouts.size());
    }

    @Test
    void mutableMatchMapsCannotChangeOtherMatchesOrCatalogTemplates() {
        GameLevelDefinition level = catalog.getById(1);
        GameMapResponse first = level.createMap();
        GameMapResponse second = level.createMap();
        int original = second.seed().get(3).get(4);

        first.seed().get(3).set(4, 0);

        assertEquals(original, second.seed().get(3).get(4));
        assertEquals(original, level.createMap().seed().get(3).get(4));
        assertThrows(UnsupportedOperationException.class, () -> catalog.getLevels().clear());
        assertThrows(UnsupportedOperationException.class, () -> level.getRows().clear());
        assertEquals(1, catalog.getById(null).getId());
        assertThrows(BusinessException.class, () -> catalog.getById(0));
        assertThrows(BusinessException.class, () -> catalog.getById(6));
    }

    @Test
    void invalidMaterialsDimensionsEnemyRulesAndVersionAreRejected() throws IOException {
        String json = configuration();
        assertThrows(IllegalStateException.class, () -> load(json.replaceFirst("22222222222222222222222222", "22222222222222222222222226")));
        assertThrows(IllegalStateException.class, () -> load(json.replaceFirst("22222222222222222222222222", "2222222222222222222222222")));
        assertThrows(IllegalStateException.class, () -> load(json.replaceFirst("\"enemyTotal\":\\s*8", "\"enemyTotal\":0")));
        assertThrows(IllegalStateException.class, () -> load(json.replaceFirst("\"spawnIntervalMillis\":\\s*2400", "\"spawnIntervalMillis\":51")));
        assertThrows(IllegalStateException.class, () -> load(json.replaceFirst("\"version\":\\s*1", "\"version\":2")));
        assertThrows(IllegalStateException.class, () -> new GameLevelCatalog(null));
    }

    private GameLevelCatalog load(String json) {
        ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return new GameLevelCatalog(input);
    }

    private String configuration() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/game/maps/levels.json")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Set<Integer> reachableCells(GameLevelDefinition level) {
        GameMapCell first = level.getPlayerSpawns().get(0);
        Queue<Integer> queue = new ArrayDeque<Integer>();
        Set<Integer> visited = new HashSet<Integer>();
        int start = first.getRow() * 26 + first.getColumn();
        visited.add(start);
        queue.add(start);
        int[] rowChanges = {-1, 1, 0, 0};
        int[] columnChanges = {0, 0, -1, 1};
        while (!queue.isEmpty()) {
            int current = queue.remove();
            for (int direction = 0; direction < 4; direction++) {
                int row = current / 26 + rowChanges[direction];
                int column = current % 26 + columnChanges[direction];
                if (row < 0 || column < 0 || row >= 26 || column >= 26) continue;
                if (row == level.getBase().getRow() && column == level.getBase().getColumn()) continue;
                char material = level.getRows().get(row).charAt(column);
                if (material == '1' || material == '2' || material == '3') continue;
                int next = row * 26 + column;
                if (visited.add(next)) queue.add(next);
            }
        }
        return visited;
    }
}
