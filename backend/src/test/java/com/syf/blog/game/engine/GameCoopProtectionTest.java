package com.syf.blog.game.engine;

import com.syf.blog.game.runtime.BulletState;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.PlayerInput;
import com.syf.blog.game.runtime.TankState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合作保护由服务端 tick 控制，不启动 Spring、不连接数据库。 */
class GameCoopProtectionTest {
    private final GameCoopEngine coop = new GameCoopEngine();
    private final GameCombatEngine combat = new GameCombatEngine();
    private final GameMovementEngine movement = new GameMovementEngine();

    @Test
    void spawnProtectionLastsThreeSimulationSecondsAndAbsorbsBulletsUntilTickSixty() {
        GameMatch match = cooperativeMatch();
        TankState player = match.getTanks().get(0);
        assertEquals(60L, player.getShieldUntilTick());
        assertEquals(60L, match.getTanks().get(1).getShieldUntilTick());

        addEnemyHit(match, 1L);
        // 真实时间流逝但模拟尚未推进时，加载等待不会消耗保护。
        combat.step(match, 100_000L);
        assertEquals(3, player.getHp());
        assertTrue(match.getBullets().isEmpty());

        match.setTick(59L);
        addEnemyHit(match, 2L);
        combat.step(match, 100_050L);
        assertEquals(3, player.getHp());
        assertEquals(60L, player.getShieldUntilTick(), "保护中被命中不能无限延长保护");
        assertTrue(match.getBullets().isEmpty());

        match.setTick(60L);
        addEnemyHit(match, 3L);
        combat.step(match, 100_100L);
        assertEquals(2, player.getHp());
        assertEquals(78L, player.getShieldUntilTick());
    }

    @Test
    void sameTickHitsOnlyRemoveOneHpAndHitProtectionExpiresAfterEighteenTicks() {
        GameMatch match = cooperativeMatch();
        TankState player = match.getTanks().get(0);
        match.setTick(100L);
        addEnemyHit(match, 1L);
        addEnemyHit(match, 2L);
        addEnemyHit(match, 3L);
        combat.step(match, 5000L);
        assertEquals(2, player.getHp());
        assertEquals(118L, player.getShieldUntilTick());
        assertTrue(match.getBullets().isEmpty());

        match.setTick(117L);
        addEnemyHit(match, 4L);
        combat.step(match, 100_000L);
        assertEquals(2, player.getHp());
        assertEquals(118L, player.getShieldUntilTick());
        assertTrue(match.getBullets().isEmpty());

        match.setTick(118L);
        addEnemyHit(match, 5L);
        combat.step(match, 100_050L);
        assertEquals(1, player.getHp());
        assertEquals(136L, player.getShieldUntilTick());
    }

    @Test
    void fatalDamageDoesNotLeaveADeadTankWithProtection() {
        GameMatch match = cooperativeMatch();
        TankState player = match.getTanks().get(0);
        player.setHp(1);
        match.setTick(60L);
        addEnemyHit(match, 1L);
        combat.step(match, 3000L);
        assertEquals(0, player.getHp());
        assertEquals(0L, player.getShieldUntilTick());
        assertFalse(match.isFinished(), "队友仍然存活，应继续战斗");
    }

    @Test
    void baseDestructionClearsProtectionForTheFinishedMatch() {
        GameMatch match = cooperativeMatch();
        match.getBase().setHp(0);
        combat.step(match, 1000L);
        assertTrue(match.isFinished());
        for (TankState player : match.getTanks()) {
            assertEquals(0L, player.getShieldUntilTick());
        }
    }

    @Test
    void protectedPlayersCanStillMoveAndFireButEnemiesDoNotReceiveProtection() {
        GameMatch match = cooperativeMatch();
        TankState player = match.getTanks().get(0);
        PlayerInput input = new PlayerInput(1L, 3, 1000L);
        input.setFireHeld(true);
        match.getInputs().put(player.getUserId(), input);

        movement.step(match, 1000L);
        combat.step(match, 1000L);
        assertEquals(54.0, player.getX(), 0.00001);
        assertEquals(60L, player.getShieldUntilTick());
        assertEquals(1, match.getBullets().size());

        TankState enemy = new TankState(-1L, 19.2);
        enemy.setX(120.0);
        enemy.setY(480.0);
        enemy.setHp(1);
        match.getEnemies().add(enemy);
        match.setEnemySpawned(1);
        match.getBullets().clear();
        match.getInputs().clear();
        match.getBullets().add(new BulletState(2L, 14L, 116.0, 490.0, 3));
        combat.step(match, 1050L);
        assertEquals(0, enemy.getHp());
        assertEquals(0L, enemy.getShieldUntilTick());
    }

    @Test
    void legacyPvpDoesNotGainProtectionOrLimitSameTickDamage() {
        GameMatch match = movement.createMatch("pvp-room", "pvp-match", coop.createMap(), List.of(14L, 15L));
        TankState shooter = match.getTanks().get(0);
        TankState target = match.getTanks().get(1);
        shooter.setX(48.0);
        shooter.setY(480.0);
        target.setX(96.0);
        target.setY(480.0);
        assertEquals(0L, shooter.getShieldUntilTick());
        assertEquals(0L, target.getShieldUntilTick());

        match.getBullets().add(new BulletState(1L, 14L, 92.0, 490.0, 3));
        match.getBullets().add(new BulletState(2L, 14L, 92.0, 490.0, 3));
        combat.step(match, 1000L);
        assertEquals(1, target.getHp());
        assertEquals(0L, target.getShieldUntilTick());
        assertFalse(match.isFinished());
    }

    private GameMatch cooperativeMatch() {
        GameMatch match = coop.createMatch("coop-room", "coop-match", List.of(14L, 15L));
        TankState player = match.getTanks().get(0);
        player.setX(48.0);
        player.setY(480.0);
        return match;
    }

    private void addEnemyHit(GameMatch match, long id) {
        BulletState bullet = new BulletState(id, -1L, 44.0, 490.0, 3);
        match.getBullets().add(bullet);
    }
}
