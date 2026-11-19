package com.syf.blog.game.engine;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.PlayerInput;
import com.syf.blog.game.runtime.TankState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不启动 Spring，不连接数据库，直接观察固定步长后的权威坐标。 */
class GameMovementEngineTest {

    private static final double PRECISION = 0.000001;
    private final GameMovementEngine engine = new GameMovementEngine();

    @Test
    void spawnPointsUseTheSameLargeConnectedAreaInsteadOfIsolatedCells() {
        int[][] seed = {
                {0, 1, 1, 1, 1, 1, 1, 0},
                {1, 0, 0, 0, 0, 0, 1, 1},
                {1, 0, 1, 1, 1, 0, 1, 1},
                {1, 0, 0, 4, 5, 0, 1, 1},
                {1, 1, 1, 1, 1, 1, 1, 1}
        };
        GameMatch match = createMatch(map(seed, 24));
        TankState first = match.getTanks().get(0);
        TankState second = match.getTanks().get(1);

        assertEquals(14L, first.getUserId());
        assertEquals(15L, second.getUserId());
        assertEquals(1, cell(first.getX(), 24));
        assertEquals(1, cell(first.getY(), 24));
        assertEquals(5, cell(second.getX(), 24));
        assertEquals(3, cell(second.getY(), 24));
        assertEquals(19.2, first.getSize(), PRECISION);
        assertFalse(rectanglesOverlap(first, second));
        assertEquals(0L, match.getTick());
        assertTrue(match.getInputs().isEmpty());
        assertTrue(match.getLoadedUserIds().isEmpty());
    }

    @Test
    void mapIsDeepCopiedAndItsNestedListsCannotBeMutated() {
        GameMapResponse sourceMap = map(new int[4][4], 24);
        GameMatch firstMatch = createMatch(sourceMap);
        GameMatch secondMatch = engine.createMatch("room-b", "match-b", sourceMap, List.of(16L, 17L));

        sourceMap.seed().get(1).set(1, 2);
        sourceMap.seed().get(0).clear();
        assertEquals(0, firstMatch.getMap().seed().get(1).get(1));
        assertEquals(4, firstMatch.getMap().seed().get(0).size());
        assertEquals(0, secondMatch.getMap().seed().get(1).get(1));
        assertThrows(UnsupportedOperationException.class,
                () -> firstMatch.getMap().seed().get(1).set(1, 3));
        assertThrows(UnsupportedOperationException.class,
                () -> firstMatch.getMap().seed().clear());
    }

    @Test
    void eachDirectionMovesExactlySixPixelsPerServerStep() {
        double[] expectedX = {96.0, 96.0, 90.0, 102.0};
        double[] expectedY = {90.0, 102.0, 96.0, 96.0};
        for (int direction = 0; direction <= 3; direction++) {
            GameMatch match = openMatch();
            TankState tank = match.getTanks().get(0);
            place(tank, 96.0, 96.0);
            place(match.getTanks().get(1), 240.0, 240.0);
            setInput(match, tank, 1L, direction, 1000L);

            engine.step(match, 1000L);

            assertEquals(expectedX[direction], tank.getX(), PRECISION);
            assertEquals(expectedY[direction], tank.getY(), PRECISION);
            assertEquals(direction, tank.getDirection());
            assertEquals(1L, tank.getLastInputSeq());
            assertEquals(1L, match.getTick());
        }
    }

    @Test
    void missingInputAndExplicitStopKeepPositionAndFacing() {
        GameMatch match = openMatch();
        TankState tank = match.getTanks().get(0);
        place(tank, 96.0, 96.0);
        tank.setDirection(2);
        engine.step(match, 1000L);
        assertEquals(96.0, tank.getX(), PRECISION);
        assertEquals(96.0, tank.getY(), PRECISION);

        setInput(match, tank, 5L, null, 1000L);
        engine.step(match, 1000L);
        assertEquals(96.0, tank.getX(), PRECISION);
        assertEquals(96.0, tank.getY(), PRECISION);
        assertEquals(2, tank.getDirection());
        assertEquals(5L, tank.getLastInputSeq());
        assertEquals(2L, match.getTick());
    }

    @Test
    void inputExpiresAfterFiveHundredMillisecondsAndFutureInputIsNotApplied() {
        GameMatch match = openMatch();
        TankState tank = match.getTanks().get(0);
        place(tank, 96.0, 96.0);
        place(match.getTanks().get(1), 240.0, 240.0);
        setInput(match, tank, 1L, 3, 1000L);

        engine.step(match, 1500L);
        assertEquals(102.0, tank.getX(), PRECISION);
        engine.step(match, 1501L);
        assertEquals(102.0, tank.getX(), PRECISION);

        setInput(match, tank, 2L, 3, 3000L);
        engine.step(match, 2000L);
        assertEquals(102.0, tank.getX(), PRECISION);
    }

    @Test
    void staleSequenceAndInvalidDirectionCannotMoveTheTank() {
        GameMatch match = openMatch();
        TankState tank = match.getTanks().get(0);
        place(tank, 96.0, 96.0);
        tank.setLastInputSeq(5L);
        setInput(match, tank, 4L, 3, 1000L);
        engine.step(match, 1000L);
        assertEquals(96.0, tank.getX(), PRECISION);
        assertEquals(5L, tank.getLastInputSeq());

        setInput(match, tank, 6L, 9, 1000L);
        engine.step(match, 1000L);
        assertEquals(96.0, tank.getX(), PRECISION);
        assertEquals(96.0, tank.getY(), PRECISION);
    }

    @Test
    void mapBoundaryClampsMovementInAllFourDirections() {
        for (int direction = 0; direction <= 3; direction++) {
            GameMatch match = openMatch();
            TankState tank = match.getTanks().get(0);
            place(tank, 96.0, 96.0);
            place(match.getTanks().get(1), 240.0, 240.0);
            double maximum = 12 * 24 - tank.getSize();
            if (direction == 0) {
                tank.setY(1.0);
            } else if (direction == 1) {
                tank.setY(maximum - 1.0);
            } else if (direction == 2) {
                tank.setX(1.0);
            } else {
                tank.setX(maximum - 1.0);
            }
            setInput(match, tank, 1L, direction, 1000L);

            engine.step(match, 1000L);

            if (direction == 0) {
                assertEquals(0.0, tank.getY(), PRECISION);
            } else if (direction == 1) {
                assertEquals(maximum, tank.getY(), PRECISION);
            } else if (direction == 2) {
                assertEquals(0.0, tank.getX(), PRECISION);
            } else {
                assertEquals(maximum, tank.getX(), PRECISION);
            }
        }
    }

    @Test
    void brickSteelAndWaterStopAtTheSurfaceWithoutCreepingThrough() {
        for (int material = 1; material <= 3; material++) {
            int[][] seed = new int[12][12];
            seed[4][5] = material;
            GameMatch match = createMatch(map(seed, 24));
            TankState tank = match.getTanks().get(0);
            place(tank, 120.0 - tank.getSize() - 2.0, 98.4);
            place(match.getTanks().get(1), 240.0, 240.0);

            advanceWithFreshInput(match, tank, 3, 100);

            assertEquals(120.0 - tank.getSize(), tank.getX(), PRECISION);
            assertEquals(98.4, tank.getY(), PRECISION);
            assertEquals(3, tank.getDirection());
            assertEquals(100L, match.getTick());
        }
    }

    @Test
    void wallsStopBothHorizontalAndVerticalApproachesAtExactContact() {
        for (int direction = 0; direction <= 3; direction++) {
            int[][] seed = new int[12][12];
            seed[5][5] = 1;
            GameMatch match = createMatch(map(seed, 24));
            TankState tank = match.getTanks().get(0);
            place(match.getTanks().get(1), 240.0, 240.0);
            double beforeWall = 120.0 - tank.getSize() - 2.0;
            if (direction == 0) {
                place(tank, 122.4, 146.0);
            } else if (direction == 1) {
                place(tank, 122.4, beforeWall);
            } else if (direction == 2) {
                place(tank, 146.0, 122.4);
            } else {
                place(tank, beforeWall, 122.4);
            }
            setInput(match, tank, 1L, direction, 1000L);

            engine.step(match, 1000L);

            if (direction == 0) {
                assertEquals(144.0, tank.getY(), PRECISION);
            } else if (direction == 1) {
                assertEquals(120.0 - tank.getSize(), tank.getY(), PRECISION);
            } else if (direction == 2) {
                assertEquals(144.0, tank.getX(), PRECISION);
            } else {
                assertEquals(120.0 - tank.getSize(), tank.getX(), PRECISION);
            }
        }
    }

    @Test
    void forestAndIceRemainPassable() {
        for (int material = 4; material <= 5; material++) {
            int[][] seed = new int[12][12];
            seed[4][5] = material;
            GameMatch match = createMatch(map(seed, 24));
            TankState tank = match.getTanks().get(0);
            double initialX = 120.0 - tank.getSize() - 2.0;
            place(tank, initialX, 98.4);
            place(match.getTanks().get(1), 240.0, 240.0);
            setInput(match, tank, 1L, 3, 1000L);

            engine.step(match, 1000L);

            assertEquals(initialX + 6.0, tank.getX(), PRECISION);
            assertTrue(tank.getX() + tank.getSize() > 120.0);
        }
    }

    @Test
    void touchingTheSideOfAWallDoesNotBlockParallelMovement() {
        int[][] seed = new int[12][12];
        seed[5][5] = 1;
        GameMatch match = createMatch(map(seed, 24));
        TankState tank = match.getTanks().get(0);
        place(tank, 118.0, 144.0);
        place(match.getTanks().get(1), 240.0, 240.0);
        setInput(match, tank, 1L, 3, 1000L);

        engine.step(match, 1000L);

        assertEquals(124.0, tank.getX(), PRECISION);
        assertEquals(144.0, tank.getY(), PRECISION);
    }

    @Test
    void tanksMovingHeadOnCannotOverlapOrPassEachOther() {
        GameMatch match = openMatch();
        TankState first = match.getTanks().get(0);
        TankState second = match.getTanks().get(1);
        place(first, 80.0, 96.0);
        place(second, 80.0 + first.getSize() + 2.0, 96.0);
        for (int index = 0; index < 50; index++) {
            long nowMillis = 1000L + index * GameMovementEngine.STEP_MILLIS;
            setInput(match, first, index + 1L, 3, nowMillis);
            setInput(match, second, index + 1L, 2, nowMillis);
            engine.step(match, nowMillis);
            assertFalse(rectanglesOverlap(first, second));
        }

        assertEquals(82.0, first.getX(), PRECISION);
        assertEquals(first.getX() + first.getSize(), second.getX(), PRECISION);
        assertTrue(first.getX() < second.getX());
    }

    @Test
    void smallTilesAndRepeatedMovementCannotJumpAcrossAThinWall() {
        int[][] seed = new int[10][10];
        seed[2][3] = 2;
        GameMatch match = createMatch(map(seed, 8));
        TankState tank = match.getTanks().get(0);
        place(tank, 24.0 - tank.getSize() - 0.25, 16.8);
        place(match.getTanks().get(1), 72.0, 72.0);

        advanceWithFreshInput(match, tank, 3, 100);

        assertEquals(24.0 - tank.getSize(), tank.getX(), PRECISION);
        assertTrue(tank.getX() + tank.getSize() <= 24.0 + PRECISION);
        assertNotEquals(0L, tank.getLastInputSeq());
    }

    @Test
    void mapsWithoutRoomForTwoConnectedSpawnsAreRejected() {
        GameMapResponse allWalls = map(new int[][] {{1, 2}, {3, 1}}, 24);
        GameMapResponse isolatedCells = map(new int[][] {{0, 1, 0}, {1, 0, 1}}, 24);
        GameMapResponse singleFloor = map(new int[][] {{0}}, 24);
        List<GameMapResponse> maps = List.of(allWalls, isolatedCells, singleFloor);
        for (GameMapResponse invalid : maps) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> createMatch(invalid));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        }
    }

    @Test
    void malformedAndUnboundedMapsAreRejectedBeforeCreatingRuntimeState() {
        List<GameMapResponse> maps = new ArrayList<GameMapResponse>();
        maps.add(null);
        maps.add(new GameMapResponse(1L, "map", null, List.of(List.of(0, 0))));
        maps.add(map(new int[2][2], 7));
        maps.add(map(new int[2][2], 65));
        maps.add(new GameMapResponse(1L, "map", 24, null));
        maps.add(new GameMapResponse(1L, "map", 24, List.of()));
        maps.add(new GameMapResponse(1L, "map", 24, List.of(List.of())));
        maps.add(map(new int[129][2], 24));
        maps.add(map(new int[2][129], 24));
        maps.add(map(new int[][] {{0, 0}, {0}}, 24));
        maps.add(new GameMapResponse(1L, "map", 24, Arrays.asList(List.of(0, 0), null)));
        maps.add(new GameMapResponse(1L, "map", 24, List.of(Arrays.asList(0, null))));
        maps.add(map(new int[][] {{0, -1}}, 24));
        maps.add(map(new int[][] {{0, 6}}, 24));
        for (GameMapResponse invalid : maps) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> createMatch(invalid));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        }
    }

    @Test
    void matchRequiresTwoDistinctValidPlayers() {
        GameMapResponse map = map(new int[4][4], 24);
        List<List<Long>> userLists = new ArrayList<List<Long>>();
        userLists.add(null);
        userLists.add(List.of(14L));
        userLists.add(List.of(14L, 14L));
        userLists.add(List.of(14L, 0L));
        userLists.add(Arrays.asList(14L, null));
        userLists.add(List.of(14L, 15L, 16L));
        for (List<Long> userIds : userLists) {
            assertThrows(BusinessException.class,
                    () -> engine.createMatch("room", "match", map, userIds));
        }
    }

    private GameMatch openMatch() {
        GameMatch match = createMatch(map(new int[12][12], 24));
        return match;
    }

    private GameMatch createMatch(GameMapResponse map) {
        GameMatch match = engine.createMatch("room", "match", map, List.of(14L, 15L));
        return match;
    }

    private GameMapResponse map(int[][] cells, int tileSize) {
        List<List<Integer>> seed = new ArrayList<List<Integer>>();
        for (int[] sourceRow : cells) {
            List<Integer> row = new ArrayList<Integer>();
            for (int material : sourceRow) {
                row.add(material);
            }
            seed.add(row);
        }
        GameMapResponse map = new GameMapResponse(1L, "test-map", tileSize, seed);
        return map;
    }

    private void setInput(GameMatch match, TankState tank, long seq, Integer direction, long nowMillis) {
        PlayerInput input = new PlayerInput(seq, direction, nowMillis);
        match.getInputs().put(tank.getUserId(), input);
    }

    private void advanceWithFreshInput(GameMatch match, TankState tank, int direction, int count) {
        for (int index = 0; index < count; index++) {
            long nowMillis = 1000L + index * GameMovementEngine.STEP_MILLIS;
            setInput(match, tank, index + 1L, direction, nowMillis);
            engine.step(match, nowMillis);
        }
    }

    private void place(TankState tank, double x, double y) {
        tank.setX(x);
        tank.setY(y);
    }

    private int cell(double coordinate, int tileSize) {
        int cell = (int) Math.floor(coordinate / tileSize);
        return cell;
    }

    private boolean rectanglesOverlap(TankState first, TankState second) {
        boolean overlap = first.getX() < second.getX() + second.getSize() - PRECISION
                && first.getX() + first.getSize() > second.getX() + PRECISION
                && first.getY() < second.getY() + second.getSize() - PRECISION
                && first.getY() + first.getSize() > second.getY() + PRECISION;
        return overlap;
    }
}
