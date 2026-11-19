package com.syf.blog.game.engine;

import com.syf.blog.game.runtime.BulletState;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.TankState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 真实服务端引擎，不访问数据库；显式推进时间验证合作规则。 */
class GameCoopEngineTest {
    private final GameCoopEngine coop = new GameCoopEngine();
    private final GameMovementEngine movement = new GameMovementEngine();
    private final GameCombatEngine combat = new GameCombatEngine();

    @Test
    void playersSpawnBesideTheirSharedBaseWithSeparateHumanAndNpcIdentities() {
        GameMatch match = fresh();
        assertEquals("COOP", match.getMode());
        assertEquals(26, match.getMap().seed().size());
        assertEquals(26, match.getMap().seed().get(0).size());
        assertEquals(3, match.getTanks().get(0).getHp());
        assertEquals(3, match.getTanks().get(1).getHp());
        assertEquals(1, match.getBase().getHp());
        assertTrue(match.getTanks().get(0).getX() < match.getBase().getX());
        assertTrue(match.getTanks().get(1).getX() > match.getBase().getX());
        coop.step(match, 1000L);
        assertEquals(2, match.getTanks().size());
        assertEquals(-1L, match.getEnemies().get(0).getUserId());
        assertEquals(1, match.getEnemies().get(0).getHp());
    }

    @Test
    void friendlyBulletPassesThroughTeammateAndCanStillKillAnEnemy() {
        GameMatch match = fresh();
        position(match.getTanks().get(0), 48, 480);
        position(match.getTanks().get(1), 96, 480);
        TankState enemy = enemy(match, -1L, 156, 480);
        match.getBullets().add(new BulletState(1L, 14L, 90, 490, 3));
        for (int tick = 1; tick < 6; tick++) {
            match.setTick(tick);
            combat.step(match, tick * 50L);
        }
        assertEquals(3, match.getTanks().get(1).getHp());
        assertEquals(0, enemy.getHp());
        assertFalse(match.isFinished());
    }

    @Test
    void enemyBulletPassesThroughOtherEnemiesAndHurtsAHuman() {
        GameMatch match = fresh();
        // 这个测试只验证阵营与伤害，显式结束开局保护。
        match.getTanks().get(0).setShieldUntilTick(0L);
        position(match.getTanks().get(0), 156, 480);
        enemy(match, -1L, 48, 480);
        TankState other = enemy(match, -2L, 96, 480);
        match.getBullets().add(new BulletState(1L, -1L, 90, 490, 3));
        for (int tick = 1; tick < 6; tick++) {
            match.setTick(tick);
            combat.step(match, tick * 50L);
        }
        assertEquals(1, other.getHp());
        assertEquals(2, match.getTanks().get(0).getHp());
    }

    @Test
    void brickProtectionBlocksEnemyFireBeforeBaseCanBeDestroyed() {
        GameMatch match = fresh();
        match.getBullets().add(new BulletState(1L, -1L, 324, 547, 1));
        combat.step(match, 1000L);
        assertEquals(1, match.getBase().getHp());
        assertTrue(match.getDestroyedCells().contains(23 * 26 + 13));
        match.getBullets().add(new BulletState(2L, -1L, 324, 572, 1));
        combat.step(match, 1050L);
        assertEquals(0, match.getBase().getHp());
        assertEquals("DEFEAT", match.getTeamResult());
        assertEquals("BASE_DESTROYED", match.getEndReason());
        assertNull(match.getWinnerUserId());
    }

    @Test
    void playerBulletCannotDamageBase() {
        GameMatch match = fresh();
        match.getDestroyedCells().add(23 * 26 + 13);
        match.getBullets().add(new BulletState(1L, 14L, 324, 572, 1));
        combat.step(match, 1000L);
        assertEquals(1, match.getBase().getHp());
        assertFalse(match.isFinished());
    }

    @Test
    void oneDestroyedPlayerSpectatesButBothDestroyedPlayersLoseTogether() {
        GameMatch match = fresh();
        match.getTanks().get(0).setHp(0);
        combat.step(match, 1000L);
        assertFalse(match.isFinished());
        match.getTanks().get(1).setHp(0);
        combat.step(match, 1050L);
        assertTrue(match.isFinished());
        assertEquals("DEFEAT", match.getTeamResult());
        assertEquals("TEAM_DESTROYED", match.getEndReason());
    }

    @Test
    void clearingEightEnemiesWinsTogetherAndCannotProduceADifferentLaterResult() {
        GameMatch match = fresh();
        for (long id = 1; id <= 8; id++) enemy(match, -id, 48, 48).setHp(0);
        combat.step(match, 1000L);
        assertTrue(match.isFinished());
        assertEquals("VICTORY", match.getTeamResult());
        assertEquals("ENEMIES_CLEARED", match.getEndReason());
        assertNull(match.getWinnerUserId());
        match.getBase().setHp(0);
        combat.step(match, 1050L);
        assertEquals("VICTORY", match.getTeamResult());
    }

    @Test
    void timeLimitIsSharedDefeat() {
        GameMatch match = fresh();
        match.setTick(3600L);
        combat.step(match, 180000L);
        assertEquals("DEFEAT", match.getTeamResult());
        assertEquals("TIME_LIMIT", match.getEndReason());
    }

    @Test
    void wavesNeverExceedThreeLivingOrEightTotalEnemies() {
        GameMatch match = fresh();
        for (int index = 0; index < 12; index++) {
            match.setTick(index * 100L);
            coop.step(match, index * 5000L);
        }
        assertEquals(3, match.getEnemies().size());
        while (match.getEnemySpawned() < 8) {
            for (TankState enemy : match.getEnemies()) enemy.setHp(0);
            match.setTick(match.getTick() + 100L);
            coop.step(match, match.getTick() * 50L);
        }
        for (TankState enemy : match.getEnemies()) enemy.setHp(0);
        match.setTick(match.getTick() + 100L);
        coop.step(match, match.getTick() * 50L);
        assertEquals(8, match.getEnemies().size());
        assertEquals(8, match.getEnemySpawned());
    }

    @Test
    void humansAndEnemiesShareSolidTankAndBaseCollisions() {
        GameMatch match = fresh();
        TankState human = match.getTanks().get(0);
        position(human, 48, 480);
        enemy(match, -1L, 72, 480);
        double moved = movement.moveTank(match, human, 3, 100);
        assertEquals(4.8, moved, 0.00001);
        position(human, 13 * 24 + 2.4, 24 * 24 - 30);
        match.getDestroyedCells().add(23 * 26 + 13);
        moved = movement.moveTank(match, human, 1, 100);
        assertEquals(10.8, moved, 0.00001);
    }

    @Test
    void unattendedAiMovesWithoutEnteringSolidTilesAndCanEndTheBattle() {
        GameMatch match = fresh();
        double firstX = 0;
        double firstY = 0;
        boolean moved = false;
        for (int tick = 0; tick < 3600 && !match.isFinished(); tick++) {
            coop.step(match, tick * 50L);
            if (tick == 0) {
                firstX = match.getEnemies().get(0).getX();
                firstY = match.getEnemies().get(0).getY();
            } else {
                TankState first = match.getEnemies().get(0);
                if (Math.abs(first.getX() - firstX) > 10 || Math.abs(first.getY() - firstY) > 10) moved = true;
            }
            movement.step(match, tick * 50L);
            combat.step(match, tick * 50L);
            for (TankState tank : match.allTanks()) {
                if (tank.getHp() <= 0) continue;
                int left = (int) (tank.getX() / 24);
                int right = (int) ((tank.getX() + tank.getSize() - 0.001) / 24);
                int top = (int) (tank.getY() / 24);
                int bottom = (int) ((tank.getY() + tank.getSize() - 0.001) / 24);
                for (int row = top; row <= bottom; row++) {
                    for (int column = left; column <= right; column++) {
                        int material = match.getMap().seed().get(row).get(column);
                        assertTrue(material == 0 || material == 4 || material == 5 ||
                                material == 1 && match.getDestroyedCells().contains(row * 26 + column));
                    }
                }
            }
        }
        assertTrue(moved);
        assertTrue(match.isFinished());
        assertEquals("DEFEAT", match.getTeamResult());
        assertNotEquals("TIME_LIMIT", match.getEndReason(), "AI 应能实际进攻并摧毁基地或玩家");
    }

    private GameMatch fresh() {
        return coop.createMatch("coop-room", "coop-match", List.of(14L, 15L));
    }

    private TankState enemy(GameMatch match, long id, double x, double y) {
        TankState enemy = new TankState(id, 19.2);
        enemy.setHp(1);
        position(enemy, x, y);
        match.getEnemies().add(enemy);
        match.setEnemySpawned(match.getEnemies().size());
        return enemy;
    }

    private void position(TankState tank, double x, double y) {
        tank.setX(x);
        tank.setY(y);
    }
}
