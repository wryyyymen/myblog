package com.syf.blog.game.engine;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameArenaMapFactoryTest {
    private final GameArenaMapFactory factory = new GameArenaMapFactory();

    @Test
    void smallPlayableDemoUsesArenaWithoutChangingSource() {
        GameMapResponse source = new GameMapResponse(1L, "材质演示", 24,
                List.of(List.of(1, 1, 1, 1, 1, 1), List.of(1, 0, 0, 4, 5, 1),
                        List.of(1, 0, 0, 2, 3, 1), List.of(1, 1, 1, 1, 1, 1)));
        GameMapResponse arena = factory.forOnlineMatch(source);
        assertEquals(20, arena.seed().size());
        assertEquals(30, arena.seed().get(0).size());
        assertEquals(24, arena.tileSize());
        assertEquals(4, source.seed().size());
        assertEquals(6, source.seed().get(0).size());
    }

    @Test
    void normalDatabaseMapIsKept() {
        List<List<Integer>> seed = new ArrayList<List<Integer>>();
        for (int row = 0; row < 8; row++) {
            seed.add(List.of(0, 0, 0, 0, 0, 0, 0, 0));
        }
        GameMapResponse source = new GameMapResponse(2L, "正常地图", 24, seed);
        assertSame(source, factory.forOnlineMatch(source));
    }

    @Test
    void invalidSmallMapIsNotSilentlyReplaced() {
        GameMapResponse source = new GameMapResponse(1L, "无出生点", 24,
                List.of(List.of(1, 1), List.of(1, 1)));
        assertSame(source, factory.forOnlineMatch(source));
        GameMovementEngine engine = new GameMovementEngine();
        assertThrows(BusinessException.class,
                () -> engine.createMatch("room", "match", source, List.of(14L, 15L)));
    }

    @Test
    void arenaSupportsTwoSpawnsAndAllMaterials() {
        GameMapResponse arena = factory.createArena();
        Set<Integer> materials = new HashSet<Integer>();
        for (List<Integer> row : arena.seed()) {
            materials.addAll(row);
        }
        assertTrue(materials.containsAll(List.of(0, 1, 2, 3, 4, 5)));
        GameMovementEngine engine = new GameMovementEngine();
        assertEquals(2, engine.createMatch("room", "match", arena, List.of(14L, 15L)).getTanks().size());
    }
}
