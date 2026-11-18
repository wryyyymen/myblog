package com.syf.blog.game.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子弹规则的单元测试，不启动 Spring、不连接数据库。
 *
 * <p>期望值按前端常量手算：子弹 12 像素、坦克 24 像素、
 * 子弹速度 240 像素/秒、冷却 300 毫秒、每人最多 3 颗。
 */
class BulletRulesTest {

    private static final double TANK_SIZE = 24;
    private static final double BULLET_SIZE = 12;
    private static final WorldBounds WORLD = new WorldBounds(960, 624);
    private static final double EPS = 1e-9;

    @ParameterizedTest
    @CsvSource({
            "UP,    106,  94",
            "DOWN,  106, 118",
            "LEFT,   94, 106",
            "RIGHT, 118, 106"
    })
    void spawnsBulletAtMuzzleInEachDirection(String directionName, double expectedX, double expectedY) {
        // 坦克左上角 (100, 100)、边长 24：中心在 (112, 112)，
        // 子弹居中后左上角是 (106, 106)，再按方向推出 12 像素
        BulletState bullet = BulletRules.spawn(
                new Position(100, 100), TANK_SIZE, Direction.valueOf(directionName), "alice", 1L);

        assertEquals(expectedX, bullet.x(), EPS);
        assertEquals(expectedY, bullet.y(), EPS);
        assertEquals("alice", bullet.ownerId());
        assertEquals(1L, bullet.id());
    }

    @Test
    void advanceMovesBySpeedTimesDelta() {
        // 0.05 秒 × 240 像素/秒 = 12 像素
        BulletState bullet = new BulletState(1L, 100, 100, Direction.RIGHT, "alice");

        List<BulletState> advanced = BulletRules.advance(List.of(bullet), 0.05);

        assertEquals(112, advanced.get(0).x(), EPS);
        assertEquals(100, advanced.get(0).y(), EPS);
    }

    @Test
    void advanceCapsOneStepAtMaxStepSeconds() {
        // 传入 5 秒也只推进 0.1 秒，即 24 像素
        BulletState bullet = new BulletState(1L, 100, 100, Direction.RIGHT, "alice");

        List<BulletState> advanced = BulletRules.advance(List.of(bullet), 5);

        assertEquals(124, advanced.get(0).x(), EPS);
    }

    @Test
    void advanceDoesNotMutateInputList() {
        // 推进返回新对象，传入的子弹坐标应保持原值，方便同一 tick 里复用
        BulletState bullet = new BulletState(1L, 100, 100, Direction.RIGHT, "alice");

        BulletRules.advance(List.of(bullet), 0.05);

        assertEquals(100, bullet.x(), EPS);
    }

    @Test
    void keepsBulletThatStillOverlapsWorld() {
        // 子弹边长 12，中心在 -5 时右半边仍在画面内，不应提前删除
        List<BulletState> kept = BulletRules.removeOutOfBounds(
                List.of(new BulletState(1L, -5, 100, Direction.LEFT, "alice")), WORLD);

        assertEquals(1, kept.size());
    }

    @Test
    void removesBulletFullyOutsideEachEdge() {
        // 完全离开需要超过半个子弹身位（6 像素），
        // 所以右侧用 width + 12、底部用 height + 12，确保整颗子弹都在界外
        BulletState outLeft = new BulletState(1L, -7, 100, Direction.LEFT, "alice");
        BulletState outRight = new BulletState(
                2L, WORLD.width() + BULLET_SIZE, 100, Direction.RIGHT, "alice");
        BulletState outTop = new BulletState(3L, 100, -7, Direction.UP, "alice");
        BulletState outBottom = new BulletState(
                4L, 100, WORLD.height() + BULLET_SIZE, Direction.DOWN, "alice");

        List<BulletState> kept = BulletRules.removeOutOfBounds(
                List.of(outLeft, outRight, outTop, outBottom), WORLD);

        assertTrue(kept.isEmpty(), "四条边外的子弹都应被清理");
    }

    @Test
    void keepsBulletExactlyOnBoundary() {
        // 刚好压在边界上时仍算在场内，和前端判定保持一致
        BulletState onLeftEdge = new BulletState(1L, -BULLET_SIZE / 2, 100, Direction.LEFT, "alice");

        List<BulletState> kept = BulletRules.removeOutOfBounds(List.of(onLeftEdge), WORLD);

        assertEquals(1, kept.size());
    }

    @Test
    void rejectsFireWithinCooldown() {
        // 只过了 200 毫秒，还没到 300 毫秒冷却
        assertFalse(BulletRules.canFire(0, 1000L, 1200L));
    }

    @Test
    void allowsFireAtExactlyCooldown() {
        // 边界值：正好满 300 毫秒应当放行
        assertTrue(BulletRules.canFire(0, 1000L, 1300L));
    }

    @Test
    void allowsFireWhileUnderBulletLimit() {
        // 上限 3 颗，场上有 2 颗时冷却已过仍可继续开火
        assertTrue(BulletRules.canFire(2, 0L, 10_000L));
    }

    @Test
    void rejectsFireWhenBulletLimitReached() {
        // 场上已有 3 颗，达到上限，冷却再久也不能再发
        assertFalse(BulletRules.canFire(3, 0L, 10_000L));
    }

    @Test
    void allowsFireAfterSystemClockMovesBackwards() {
        // 时间戳回退时按冷却已结束处理，否则玩家会永久开不出火
        assertTrue(BulletRules.canFire(0, 5000L, 1000L));
    }

    @Test
    void countsOnlyMatchingOwner() {
        List<BulletState> bullets = List.of(
                new BulletState(1L, 0, 0, Direction.UP, "alice"),
                new BulletState(2L, 0, 0, Direction.UP, "bob"),
                new BulletState(3L, 0, 0, Direction.UP, "alice"));

        assertEquals(2, BulletRules.countOwned(bullets, "alice"));
        assertEquals(1, BulletRules.countOwned(bullets, "bob"));
        assertEquals(0, BulletRules.countOwned(bullets, "carol"));
    }
}
