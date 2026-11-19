package com.syf.blog.game.engine;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.game.runtime.BulletState;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.PlayerInput;
import com.syf.blog.game.runtime.TankState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 直接验证服务端战斗规则，不启动 Spring 或读取数据库。 */
class GameCombatEngineTest {

    private static final double PRECISION = 0.000001;
    private final GameCombatEngine combat = new GameCombatEngine();
    private final GameMovementEngine movement = new GameMovementEngine();

    @Test
    void freshHeldFireCreatesOneBulletAndWaitsSevenTicksForTheNext() {
        GameMatch match = openMatch();
        TankState tank = match.getTanks().get(0);
        setInput(match, tank, 1L, null, 1000L, true);
        match.setTick(1L);

        combat.step(match, 1000L);
        assertEquals(1, match.getBullets().size());
        assertEquals(14L, match.getBullets().get(0).getOwnerUserId());
        assertEquals(8L, tank.getNextFireTick());

        for (long tick = 2L; tick <= 7L; tick++) {
            match.setTick(tick);
            combat.step(match, 1000L);
            assertEquals(1, match.getBullets().size());
        }
        match.setTick(8L);
        combat.step(match, 1000L);
        assertEquals(2, match.getBullets().size());
        assertEquals(15L, tank.getNextFireTick());
    }

    @Test
    void releaseMissingStaleAndFutureInputsCannotGenerateBullets() {
        GameMatch match = openMatch();
        TankState tank = match.getTanks().get(0);
        match.setTick(1L);
        combat.step(match, 1000L);
        assertTrue(match.getBullets().isEmpty());

        setInput(match, tank, 1L, null, 1000L, false);
        combat.step(match, 1000L);
        assertTrue(match.getBullets().isEmpty());
        setInput(match, tank, 2L, null, 1000L, true);
        combat.step(match, 1501L);
        assertTrue(match.getBullets().isEmpty());
        setInput(match, tank, 3L, null, 2000L, true);
        combat.step(match, 1000L);
        assertTrue(match.getBullets().isEmpty());
        tank.setLastInputSeq(5L);
        setInput(match, tank, 4L, null, 1000L, true);
        combat.step(match, 1000L);
        assertTrue(match.getBullets().isEmpty());
    }

    @Test
    void aPlayerCannotHaveMoreThanFourActiveBullets() {
        GameMatch match = openMatch();
        TankState tank = match.getTanks().get(0);
        for (int index = 0; index < 4; index++) {
            match.getBullets().add(new BulletState(index + 1L, 14L, 200.0 + index * 10, 200.0, 3));
        }
        match.setNextBulletId(5L);
        setInput(match, tank, 1L, null, 1000L, true);
        match.setTick(1L);

        combat.step(match, 1000L);

        assertEquals(4, match.getBullets().size());
        assertEquals(5L, match.getNextBulletId());
        assertEquals(0L, tank.getNextFireTick());
    }

    @Test
    void bulletMovesEighteenPixelsPerStepAndCannotHurtItsOwner() {
        GameMatch match = openMatch();
        TankState tank = match.getTanks().get(0);
        BulletState bullet = new BulletState(1L, 14L, tank.getX() + 5.0, tank.getY() + 5.0, 3);
        match.getBullets().add(bullet);

        combat.step(match, 1000L);

        assertEquals(tank.getX() + 23.0, bullet.getX(), PRECISION);
        assertEquals(3, tank.getHp());
        assertEquals(1, match.getBullets().size());
    }

    @Test
    void brickIsDestroyedWithoutChangingMapTemplateAndSteelIsNotDestroyed() {
        for (int material = 1; material <= 2; material++) {
            int[][] seed = new int[12][20];
            seed[4][5] = material;
            GameMatch match = match(seed);
            match.getBullets().add(new BulletState(1L, 14L, 108.0, 105.6, 3));

            combat.step(match, 1000L);

            assertTrue(match.getBullets().isEmpty());
            assertEquals(material == 1, match.getDestroyedCells().contains(4 * 20 + 5));
            assertEquals(material, match.getMap().seed().get(4).get(5));
        }
    }

    @Test
    void waterTreesAndIceDoNotStopBullets() {
        for (int material = 3; material <= 5; material++) {
            int[][] seed = new int[12][20];
            seed[4][5] = material;
            GameMatch match = match(seed);
            BulletState bullet = new BulletState(1L, 14L, 108.0, 105.6, 3);
            match.getBullets().add(bullet);

            combat.step(match, 1000L);

            assertEquals(1, match.getBullets().size());
            assertEquals(126.0, bullet.getX(), PRECISION);
            assertTrue(match.getDestroyedCells().isEmpty());
        }
    }

    @Test
    void sweptCollisionHitsThinWallEvenWhenTheEndPointWouldBeBeyondIt() {
        List<List<Integer>> seed = rows(new int[12][30]);
        seed.get(4).set(10, 2);
        GameMapResponse map = new GameMapResponse(1L, "细墙", 8, seed);
        GameMatch match = match(map);
        match.getTanks().get(0).setX(16.0);
        match.getTanks().get(0).setY(16.0);
        match.getTanks().get(1).setX(200.0);
        match.getTanks().get(1).setY(64.0);
        match.getBullets().add(new BulletState(1L, 14L, 72.0, 36.0, 3));

        combat.step(match, 1000L);

        assertTrue(match.getBullets().isEmpty());
    }

    @Test
    void nearestWallWinsOverTankBehindIt() {
        int[][] seed = new int[12][20];
        seed[4][5] = 2;
        GameMatch match = match(seed);
        TankState target = match.getTanks().get(1);
        target.setX(125.0);
        target.setY(98.4);
        match.getBullets().add(new BulletState(1L, 14L, 108.0, 105.6, 3));

        combat.step(match, 1000L);

        assertEquals(3, target.getHp());
        assertTrue(match.getBullets().isEmpty());
    }

    @Test
    void nearestTankWinsOverWallBehindIt() {
        int[][] seed = new int[12][20];
        seed[4][6] = 1;
        GameMatch match = match(seed);
        TankState target = match.getTanks().get(1);
        target.setX(120.0);
        target.setY(98.4);
        match.getBullets().add(new BulletState(1L, 14L, 108.0, 105.6, 3));

        combat.step(match, 1000L);

        assertEquals(2, target.getHp());
        assertFalse(match.getDestroyedCells().contains(4 * 20 + 6));
        assertTrue(match.getBullets().isEmpty());
    }

    @Test
    void threeHitsDestroyTheOpponentAndFinishExactlyOnce() {
        GameMatch match = openMatch();
        TankState target = match.getTanks().get(1);
        target.setX(120.0);
        target.setY(98.4);
        for (int hit = 1; hit <= 3; hit++) {
            match.getBullets().add(new BulletState(hit, 14L, 108.0, 105.6, 3));
            match.setTick(hit);
            combat.step(match, 1000L);
            assertEquals(3 - hit, target.getHp());
        }

        assertTrue(match.isFinished());
        assertEquals(14L, match.getWinnerUserId());
        assertEquals("DESTROYED", match.getEndReason());
        assertTrue(match.getInputs().isEmpty());

        match.getBullets().add(new BulletState(99L, 15L, 40.0, 57.6, 3));
        combat.step(match, 1000L);
        assertEquals(3, match.getTanks().get(0).getHp());
        assertEquals(1, match.getBullets().size());
    }

    @Test
    void simultaneousLethalHitsProduceADrawInsteadOfFirstBulletAdvantage() {
        GameMatch match = openMatch();
        TankState first = match.getTanks().get(0);
        TankState second = match.getTanks().get(1);
        first.setX(48.0);
        first.setY(96.0);
        second.setX(120.0);
        second.setY(96.0);
        first.setHp(1);
        second.setHp(1);
        match.getBullets().add(new BulletState(1L, 14L, 108.0, 105.6, 3));
        match.getBullets().add(new BulletState(2L, 15L, 78.0, 105.6, 2));

        combat.step(match, 1000L);

        assertEquals(0, first.getHp());
        assertEquals(0, second.getHp());
        assertTrue(match.isFinished());
        assertNull(match.getWinnerUserId());
        assertEquals("DRAW", match.getEndReason());
    }

    @Test
    void matchTimeLimitProducesADrawAndClearsInputs() {
        GameMatch match = openMatch();
        setInput(match, match.getTanks().get(0), 1L, 3, 1000L, false);
        match.setTick(3599L);
        combat.step(match, 1000L);
        assertFalse(match.isFinished());

        match.setTick(3600L);
        combat.step(match, 1000L);
        assertTrue(match.isFinished());
        assertNull(match.getWinnerUserId());
        assertEquals("TIME_LIMIT", match.getEndReason());
        assertTrue(match.getInputs().isEmpty());
    }

    @Test
    void deadTankNeitherMovesNorFires() {
        GameMatch match = openMatch();
        TankState tank = match.getTanks().get(0);
        tank.setHp(0);
        setInput(match, tank, 1L, 3, 1000L, true);
        double originalX = tank.getX();

        movement.step(match, 1000L);
        combat.step(match, 1000L);

        assertEquals(originalX, tank.getX(), PRECISION);
        assertTrue(match.getBullets().isEmpty());
        assertTrue(match.isFinished());
        assertEquals(15L, match.getWinnerUserId());
    }

    @Test
    void destroyedBrickNoLongerBlocksMovementOrBullets() {
        int[][] seed = new int[12][20];
        seed[4][5] = 1;
        GameMatch match = match(seed);
        match.getDestroyedCells().add(4 * 20 + 5);
        TankState tank = match.getTanks().get(0);
        tank.setX(120.0 - tank.getSize() - 2.0);
        tank.setY(98.4);
        setInput(match, tank, 1L, 3, 1000L, false);
        match.getBullets().add(new BulletState(1L, 14L, 108.0, 105.6, 3));

        movement.step(match, 1000L);
        combat.step(match, 1000L);

        assertEquals(120.0 - tank.getSize() + 4.0, tank.getX(), PRECISION);
        assertEquals(1, match.getBullets().size());
        assertEquals(126.0, match.getBullets().get(0).getX(), PRECISION);
    }

    @Test
    void bulletLeavesWorldWithoutGrowingTheActiveList() {
        GameMatch match = openMatch();
        match.getBullets().add(new BulletState(1L, 14L, 475.0, 200.0, 3));
        combat.step(match, 1000L);
        assertTrue(match.getBullets().isEmpty());
    }

    private GameMatch openMatch() {
        return match(new int[12][20]);
    }

    private GameMatch match(int[][] cells) {
        GameMapResponse map = new GameMapResponse(1L, "测试战场", 24, rows(cells));
        return match(map);
    }

    private GameMatch match(GameMapResponse map) {
        TankState first = new TankState(14L, 19.2);
        first.setX(48.0);
        first.setY(48.0);
        first.setDirection(3);
        TankState second = new TankState(15L, 19.2);
        second.setX(384.0);
        second.setY(240.0);
        second.setDirection(2);
        return new GameMatch("room-test", "match-test", map, List.of(first, second));
    }

    private List<List<Integer>> rows(int[][] cells) {
        List<List<Integer>> rows = new ArrayList<List<Integer>>();
        for (int[] sourceRow : cells) {
            List<Integer> row = new ArrayList<Integer>();
            for (int cell : sourceRow) {
                row.add(cell);
            }
            rows.add(row);
        }
        return rows;
    }

    private void setInput(GameMatch match, TankState tank, long seq, Integer direction,
                          long receivedAtMillis, boolean fireHeld) {
        PlayerInput input = new PlayerInput(seq, direction, receivedAtMillis);
        input.setFireHeld(fireHeld);
        match.getInputs().put(tank.getUserId(), input);
    }
}
