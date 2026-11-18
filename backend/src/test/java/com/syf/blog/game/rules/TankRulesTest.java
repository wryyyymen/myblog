package com.syf.blog.game.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 坦克移动规则的单元测试，不启动 Spring、不连接数据库。
 *
 * <p>期望值都按前端的常量手算得出：速度 72 像素/秒，坦克边长 24，
 * 世界尺寸用 960 × 624（40 列 × 26 行、每格 24 像素）。
 */
class TankRulesTest {

    private static final double TANK_SIZE = 24;
    private static final WorldBounds WORLD = new WorldBounds(960, 624);

    /** 浮点比较的容差，避免 3.5999999 这类二进制误差造成误判。 */
    private static final double EPS = 1e-9;

    @ParameterizedTest
    @CsvSource({
            "UP,    0,    -3.6",
            "DOWN,  0,     3.6",
            "LEFT, -3.6,   0",
            "RIGHT, 3.6,   0"
    })
    void movesInFourDirectionsBySpeedTimesDelta(String directionName, double expectedDx, double expectedDy) {
        // 0.05 秒 × 72 像素/秒 = 3.6 像素
        Position next = TankRules.move(
                new Position(100, 100),
                Direction.valueOf(directionName),
                0.05,
                WORLD,
                TANK_SIZE,
                BlockedChecker.NONE);

        assertEquals(100 + expectedDx, next.x(), EPS);
        assertEquals(100 + expectedDy, next.y(), EPS);
    }

    @Test
    void staysInPlaceWhenDirectionIsNull() {
        // 客户端松键时 direction 为 null，位置必须保持不动
        Position start = new Position(120, 240);

        Position next = TankRules.move(start, null, 0.05, WORLD, TANK_SIZE, BlockedChecker.NONE);

        assertEquals(start, next);
    }

    @Test
    void clampsToTopLeftCorner() {
        Position next = TankRules.move(
                new Position(0, 0), Direction.LEFT, 0.1, WORLD, TANK_SIZE, BlockedChecker.NONE);

        assertEquals(0, next.x(), EPS);
        assertEquals(0, next.y(), EPS);
    }

    @Test
    void clampsToBottomRightCorner() {
        // 右下角上限 = 世界尺寸 - 坦克边长，即 936 与 600
        double maxX = WORLD.width() - TANK_SIZE;
        double maxY = WORLD.height() - TANK_SIZE;

        Position movedRight = TankRules.move(
                new Position(maxX, maxY), Direction.RIGHT, 0.1, WORLD, TANK_SIZE, BlockedChecker.NONE);
        Position movedDown = TankRules.move(
                new Position(maxX, maxY), Direction.DOWN, 0.1, WORLD, TANK_SIZE, BlockedChecker.NONE);

        assertEquals(maxX, movedRight.x(), EPS);
        assertEquals(maxY, movedRight.y(), EPS);
        assertEquals(maxX, movedDown.x(), EPS);
        assertEquals(maxY, movedDown.y(), EPS);
    }

    @Test
    void capsOneStepAtMaxStepSeconds() {
        // 传入 5 秒也只推进 0.1 秒，即 72 × 0.1 = 7.2 像素。
        // 不截断的话坦克会瞬移 360 像素，直接穿过墙体。
        Position next = TankRules.move(
                new Position(100, 100), Direction.UP, 5, WORLD, TANK_SIZE, BlockedChecker.NONE);

        assertEquals(100, next.x(), EPS);
        assertEquals(92.8, next.y(), EPS);
    }

    @Test
    void zeroOrNegativeDeltaDoesNotMove() {
        Position start = new Position(100, 100);

        assertEquals(start, TankRules.move(start, Direction.UP, 0, WORLD, TANK_SIZE, BlockedChecker.NONE));
        assertEquals(start, TankRules.move(start, Direction.UP, -1, WORLD, TANK_SIZE, BlockedChecker.NONE));
    }

    @Test
    void keepsPositionWhenTargetTileIsBlocked() {
        // 碰撞判定永远返回 true，坦克应该一步也走不动
        BlockedChecker alwaysBlocked = (x, y, size) -> true;
        Position start = new Position(100, 100);

        Position next = TankRules.move(start, Direction.RIGHT, 0.05, WORLD, TANK_SIZE, alwaysBlocked);

        assertEquals(start, next);
    }

    @Test
    void horizontalBlockDoesNotStopVerticalMove() {
        // 模拟右侧有一堵竖墙：横坐标到 102 就算被挡。
        // 起点 100 向右走 3.6 像素会到 103.6，正好撞上这堵墙。
        BlockedChecker wallOnRight = (x, y, size) -> x >= 102;

        // 向右走：被墙挡住，横坐标保持不变
        Position afterRight = TankRules.move(
                new Position(100, 100), Direction.RIGHT, 0.05, WORLD, TANK_SIZE, wallOnRight);
        assertEquals(100, afterRight.x(), EPS);
        assertEquals(100, afterRight.y(), EPS);

        // 向下走：横坐标没变，纵向不在墙体范围内，所以能正常前进
        Position afterDown = TankRules.move(
                new Position(100, 100), Direction.DOWN, 0.05, WORLD, TANK_SIZE, wallOnRight);
        assertEquals(100, afterDown.x(), EPS);
        assertEquals(103.6, afterDown.y(), EPS);
    }

    @Test
    void worldSmallerThanTankKeepsTankAtOrigin() {
        // 地图异常小于坦克时，上限取 0，坦克留在原点而不是被推到负坐标
        WorldBounds tinyWorld = new WorldBounds(10, 10);

        Position next = TankRules.move(
                new Position(0, 0), Direction.RIGHT, 0.1, tinyWorld, TANK_SIZE, BlockedChecker.NONE);

        assertEquals(0, next.x(), EPS);
        assertEquals(0, next.y(), EPS);
    }
}
