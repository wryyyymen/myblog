package com.syf.blog.game.engine;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.map.GameLevelCatalog;
import com.syf.blog.game.map.GameLevelDefinition;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.TankState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 五关共用同一引擎，验证规则真正参与运行，而不只是更换地图名称。 */
class GameCoopLevelTest {
    private final GameCoopEngine coop = new GameCoopEngine();
    private final GameCombatEngine combat = new GameCombatEngine();

    @Test
    void suppliedMapIsCopiedIntoTheMatchInsteadOfBeingRecreatedFromTheLevel() {
        GameLevelDefinition level = GameLevelCatalog.getDefault().getById(3);
        GameMapResponse template = level.createMap();
        GameMapResponse source = new GameMapResponse(template.id(), "本局已读取的地图副本",
                template.tileSize(), template.seed());

        GameMatch match = coop.createMatch("room", "match", List.of(14L, 15L), level, source);

        assertEquals(source.name(), match.getMap().name());
        assertNotSame(source, match.getMap());
        assertNotSame(source.seed(), match.getMap().seed());
        assertEquals(source.seed(), match.getMap().seed());
        int material = match.getMap().seed().get(1).get(2);
        source.seed().get(1).set(2, 2);
        assertEquals(material, match.getMap().seed().get(1).get(2));
        assertEquals(material, level.createMap().seed().get(1).get(2));
    }

    @Test
    void suppliedMapMustMatchItsLevelIdTileSizeAndCompleteLayout() {
        GameLevelDefinition level = GameLevelCatalog.getDefault().getById(3);
        GameMapResponse template = level.createMap();
        GameMapResponse wrongId = new GameMapResponse(1L, template.name(), template.tileSize(), template.seed());
        GameMapResponse wrongSize = new GameMapResponse(template.id(), template.name(), 48, template.seed());
        GameMapResponse missingRow = new GameMapResponse(template.id(), template.name(), template.tileSize(),
                template.seed().subList(0, 25));
        assertThrows(BusinessException.class,
                () -> coop.createMatch("room", "match", List.of(14L, 15L), level, wrongId));
        assertThrows(BusinessException.class,
                () -> coop.createMatch("room", "match", List.of(14L, 15L), level, wrongSize));
        assertThrows(BusinessException.class,
                () -> coop.createMatch("room", "match", List.of(14L, 15L), level, missingRow));

        template.seed().get(1).set(2, 1);
        assertThrows(BusinessException.class,
                () -> coop.createMatch("room", "match", List.of(14L, 15L), level, template));
    }

    @Test
    void eachLevelUsesItsOwnMapSpeedSpawnIntervalAndEnemyTotal() {
        for (GameLevelDefinition level : GameLevelCatalog.getDefault().getLevels()) {
            GameMatch match = coop.createMatch("room", "match", List.of(14L, 15L), level);
            assertEquals(level.getId(), match.getMap().id().intValue());
            assertEquals(level.createMap().seed(), match.getMap().seed());

            coop.step(match, 0L);
            assertEquals(1, match.getEnemySpawned());
            assertEquals(level.getSpawnIntervalMillis() / 50L, match.getNextSpawnTick());
            TankState first = match.getEnemies().get(0);
            // 第一出生点朝下通路至少有一格空地；开局未与玩家或基地对齐。
            double initialY = 24.0 + (24.0 - first.getSize()) / 2.0;
            double distance = Math.abs(first.getX() - (48.0 + (24.0 - first.getSize()) / 2.0))
                    + Math.abs(first.getY() - initialY);
            assertEquals(level.getEnemySpeed() * 0.05, distance, 0.000001, level.getName());

            match.setTick(match.getNextSpawnTick() - 1L);
            coop.step(match, match.getTick() * 50L);
            assertEquals(1, match.getEnemySpawned());
            match.setTick(match.getNextSpawnTick());
            coop.step(match, match.getTick() * 50L);
            assertEquals(2, match.getEnemySpawned());

            while (match.getEnemySpawned() < level.getEnemyTotal()) {
                for (TankState enemy : match.getEnemies()) {
                    enemy.setHp(0);
                }
                match.setTick(match.getNextSpawnTick());
                coop.step(match, match.getTick() * 50L);
            }
            for (TankState enemy : match.getEnemies()) {
                enemy.setHp(0);
            }
            match.setTick(match.getNextSpawnTick());
            coop.step(match, match.getTick() * 50L);
            assertEquals(level.getEnemyTotal(), match.getEnemySpawned());
            assertEquals(level.getEnemyTotal(), match.getEnemies().size());
            combat.step(match, match.getTick() * 50L);
            assertTrue(match.isFinished());
            assertEquals("VICTORY", match.getTeamResult(), level.getName());
        }
    }

    @Test
    void laterLevelDoesNotWinAfterOnlyTheFirstEightEnemies() {
        GameLevelDefinition level = GameLevelCatalog.getDefault().getById(5);
        GameMatch match = coop.createMatch("room", "match", List.of(14L, 15L), level);
        for (int index = 1; index <= 8; index++) {
            TankState enemy = new TankState(-Long.valueOf(index), 19.2);
            enemy.setHp(0);
            match.getEnemies().add(enemy);
        }
        combat.step(match, 1000L);
        assertFalse(match.isFinished());
        assertNull(match.getTeamResult());
    }

    @Test
    void eachLevelStopsSpawningAtItsConfiguredConcurrentLimit() {
        for (GameLevelDefinition level : GameLevelCatalog.getDefault().getLevels()) {
            GameMatch match = coop.createMatch("room", "match", List.of(14L, 15L), level);
            for (int index = 0; index <= level.getMaxAliveEnemies(); index++) {
                match.setTick(index * 200L);
                coop.step(match, match.getTick() * 50L);
                // 移出出生格，避免把出生点占用误认为并发上限。
                for (int enemyIndex = 0; enemyIndex < match.getEnemies().size(); enemyIndex++) {
                    TankState enemy = match.getEnemies().get(enemyIndex);
                    enemy.setX(48.0 + enemyIndex * 30.0);
                    enemy.setY(60.0);
                }
            }
            assertEquals(level.getMaxAliveEnemies(), match.getEnemySpawned(), level.getName());
        }
    }
}
