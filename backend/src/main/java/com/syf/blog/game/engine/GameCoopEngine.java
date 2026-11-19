package com.syf.blog.game.engine;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.game.runtime.BaseState;
import com.syf.blog.game.runtime.EnemyBrain;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.PlayerInput;
import com.syf.blog.game.runtime.TankState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

/** 合作关卡、分批出兵和 AI 决策。只操作本局内存，不查询数据库。 */
public class GameCoopEngine {
    public static final int ENEMY_TOTAL = 8;
    private static final int TILE = 24;
    private static final int BOARD = 26;
    private static final int MAX_ALIVE_ENEMIES = 3;
    private static final int[] ROW_DELTA = {-1, 1, 0, 0};
    private static final int[] COLUMN_DELTA = {0, 0, -1, 1};
    private final GameMovementEngine movement = new GameMovementEngine();

    /** 复用单人关卡的布局规则，两名玩家出生在基地两侧。 */
    public GameMatch createMatch(String roomId, String matchId, List<Long> userIds) {
        GameMapResponse map = createMap();
        // 使用共同入口验证玩家和地图，再重新安排合作出生点。
        GameMatch match = movement.createMatch(roomId, matchId, map, userIds);
        match.setMode("COOP");
        place(match.getTanks().get(0), 23, 10);
        place(match.getTanks().get(1), 23, 16);
        match.getTanks().get(0).setDirection(0);
        match.getTanks().get(1).setDirection(0);
        // 模拟从 tick=0 开始；加载等待不消耗保护，真正开局后持续 3 秒。
        long spawnProtectionTicks = GameCombatEngine.SPAWN_PROTECTION_MILLIS / GameMovementEngine.STEP_MILLIS;
        for (TankState player : match.getTanks()) {
            player.setShieldUntilTick(spawnProtectionTicks);
        }
        BaseState base = new BaseState(13.0 * TILE, 24.0 * TILE, TILE);
        match.setBase(base);
        return match;
    }

    /** 在人类移动和子弹更新前调用一次；AI 速度为 48 像素/秒。 */
    public void step(GameMatch match, long nowMillis) {
        if (match.isFinished()) return;
        spawnIfNecessary(match);
        for (TankState enemy : match.getEnemies()) {
            if (enemy.getHp() <= 0) {
                match.getInputs().remove(enemy.getUserId());
                continue;
            }
            EnemyBrain brain = match.getEnemyBrains().get(enemy.getUserId());
            Integer aim = findAim(match, enemy);
            if (aim != null) {
                enemy.setDirection(aim);
            } else {
                moveEnemy(match, enemy, brain);
            }
            // AI 的序号、时钟、开火状态都在服务端生成。
            PlayerInput input = new PlayerInput(match.getTick() + 1L, null, nowMillis);
            input.setFireHeld(true);
            match.getInputs().put(enemy.getUserId(), input);
        }
    }

    private void spawnIfNecessary(GameMatch match) {
        if (match.getEnemySpawned() >= ENEMY_TOTAL || match.getTick() < match.getNextSpawnTick()) return;
        int alive = 0;
        for (TankState enemy : match.getEnemies()) {
            if (enemy.getHp() > 0) alive++;
        }
        if (alive >= MAX_ALIVE_ENEMIES) return;
        int[] spawnColumns = {2, 13, 23};
        int first = match.getEnemySpawned() % spawnColumns.length;
        for (int attempt = 0; attempt < spawnColumns.length; attempt++) {
            int column = spawnColumns[(first + attempt) % spawnColumns.length];
            long identity = -(match.getEnemySpawned() + 1L);
            TankState enemy = new TankState(identity, TILE * 0.8);
            place(enemy, 1, column);
            boolean occupied = false;
            for (TankState other : match.allTanks()) {
                if (other.getHp() > 0 && Math.abs(other.getX() - enemy.getX()) < enemy.getSize()
                        && Math.abs(other.getY() - enemy.getY()) < enemy.getSize()) occupied = true;
            }
            if (occupied) continue;
            enemy.setHp(1);
            enemy.setDirection(1);
            enemy.setNextFireTick(match.getTick() + 20L);
            match.getEnemies().add(enemy);
            match.getEnemyBrains().put(identity, new EnemyBrain());
            match.setEnemySpawned(match.getEnemySpawned() + 1);
            match.setNextSpawnTick(match.getTick() + 50L);
            return;
        }
    }

    private void moveEnemy(GameMatch match, TankState enemy, EnemyBrain brain) {
        double centerX = enemy.getX() + enemy.getSize() / 2.0;
        double centerY = enemy.getY() + enemy.getSize() / 2.0;
        if (brain.getTargetX() == null) {
            int currentColumn = (int) (centerX / TILE);
            int currentRow = (int) (centerY / TILE);
            double cellX = currentColumn * TILE + TILE / 2.0;
            double cellY = currentRow * TILE + TILE / 2.0;
            if (Math.abs(centerX - cellX) > 0.001 || Math.abs(centerY - cellY) > 0.001) {
                // 被另一辆坦克挡在格子中间时，先回到当前格中心再选路。
                brain.setTargetX(cellX);
                brain.setTargetY(cellY);
            } else {
                int next = nextCell(match, enemy, currentRow, currentColumn);
                if (next < 0) return;
                brain.setTargetX((next % BOARD) * TILE + TILE / 2.0);
                brain.setTargetY((next / BOARD) * TILE + TILE / 2.0);
            }
        }
        double differenceX = brain.getTargetX() - centerX;
        double differenceY = brain.getTargetY() - centerY;
        if (Math.abs(differenceX) < 0.001 && Math.abs(differenceY) < 0.001) {
            brain.setTargetX(null);
            brain.setTargetY(null);
            brain.setBlockedTicks(0);
            return;
        }
        int direction;
        double distance;
        if (Math.abs(differenceX) > 0.001) {
            direction = differenceX < 0 ? 2 : 3;
            distance = Math.abs(differenceX);
        } else {
            direction = differenceY < 0 ? 0 : 1;
            distance = Math.abs(differenceY);
        }
        double moved = movement.moveTank(match, enemy, direction, Math.min(2.4, distance));
        if (moved < 0.001) {
            brain.setBlockedTicks(brain.getBlockedTicks() + 1);
            if (brain.getBlockedTicks() >= 12) {
                brain.setTargetX(null);
                brain.setTargetY(null);
                brain.setBlockedTicks(0);
            }
        } else {
            brain.setBlockedTicks(0);
        }
    }

    /** BFS 寻路；砖墙允许作为路线，AI 会先开火打通，钢墙和水域不能通过。 */
    private int nextCell(GameMatch match, TankState enemy, int row, int column) {
        int targetRow = 22;
        int targetColumn = 13;
        // 每三辆中一辆追击最近玩家，其余推进到基地前方。
        if ((-enemy.getUserId()) % 3L == 0L) {
            double nearest = Double.POSITIVE_INFINITY;
            for (TankState player : match.getTanks()) {
                double distance = Math.abs(player.getX() - enemy.getX()) + Math.abs(player.getY() - enemy.getY());
                if (player.getHp() > 0 && distance < nearest) {
                    nearest = distance;
                    targetRow = (int) ((player.getY() + player.getSize() / 2.0) / TILE);
                    targetColumn = (int) ((player.getX() + player.getSize() / 2.0) / TILE);
                }
            }
        }
        int origin = row * BOARD + column;
        int target = targetRow * BOARD + targetColumn;
        int[] previous = new int[BOARD * BOARD];
        Arrays.fill(previous, -1);
        previous[origin] = origin;
        Queue<Integer> queue = new ArrayDeque<Integer>();
        queue.add(origin);
        while (!queue.isEmpty()) {
            int cell = queue.remove();
            if (cell == target) break;
            for (int direction = 0; direction < 4; direction++) {
                int nextRow = cell / BOARD + ROW_DELTA[direction];
                int nextColumn = cell % BOARD + COLUMN_DELTA[direction];
                if (nextRow <= 0 || nextRow >= BOARD - 1 || nextColumn <= 0 || nextColumn >= BOARD - 1) continue;
                int next = nextRow * BOARD + nextColumn;
                int material = match.getMap().seed().get(nextRow).get(nextColumn);
                if (previous[next] >= 0 || material == 2 || material == 3 || next == 24 * BOARD + 13) continue;
                boolean occupied = false;
                for (TankState other : match.allTanks()) {
                    if (other == enemy || other.getHp() <= 0 || next == target) continue;
                    int otherColumn = (int) ((other.getX() + other.getSize() / 2.0) / TILE);
                    int otherRow = (int) ((other.getY() + other.getSize() / 2.0) / TILE);
                    if (otherRow == nextRow && otherColumn == nextColumn) occupied = true;
                }
                if (occupied) continue;
                previous[next] = cell;
                queue.add(next);
            }
        }
        if (target == origin || previous[target] < 0) return -1;
        int next = target;
        while (previous[next] != origin) next = previous[next];
        return next;
    }

    /** 同行/同列且射线上没有钢墙时瞄准，砖墙会被先打掉。 */
    private Integer findAim(GameMatch match, TankState enemy) {
        for (TankState player : match.getTanks()) {
            if (player.getHp() <= 0) continue;
            Integer aim = aimAt(match, enemy, player.getX() + player.getSize() / 2.0,
                    player.getY() + player.getSize() / 2.0);
            if (aim != null) return aim;
        }
        BaseState base = match.getBase();
        return aimAt(match, enemy, base.getX() + base.getSize() / 2.0, base.getY() + base.getSize() / 2.0);
    }

    private Integer aimAt(GameMatch match, TankState enemy, double targetX, double targetY) {
        double x = enemy.getX() + enemy.getSize() / 2.0;
        double y = enemy.getY() + enemy.getSize() / 2.0;
        int direction;
        if (Math.abs(targetX - x) < 5.0) direction = targetY < y ? 0 : 1;
        else if (Math.abs(targetY - y) < 5.0) direction = targetX < x ? 2 : 3;
        else return null;
        int steps = (int) (Math.max(Math.abs(targetX - x), Math.abs(targetY - y)) / 6.0);
        for (int step = 1; step <= steps; step++) {
            int row = (int) ((y + ROW_DELTA[direction] * step * 6.0) / TILE);
            int column = (int) ((x + COLUMN_DELTA[direction] * step * 6.0) / TILE);
            if (match.getMap().seed().get(row).get(column) == 2) return null;
        }
        return direction;
    }

    private void place(TankState tank, int row, int column) {
        double offset = (TILE - tank.getSize()) / 2.0;
        tank.setX(column * TILE + offset);
        tank.setY(row * TILE + offset);
    }

    public GameMapResponse createMap() {
        List<List<Integer>> seed = new ArrayList<List<Integer>>();
        for (int row = 0; row < BOARD; row++) {
            List<Integer> cells = new ArrayList<Integer>();
            for (int column = 0; column < BOARD; column++) {
                int material = row == 0 || column == 0 || row == BOARD - 1 || column == BOARD - 1 ? 2 : 0;
                cells.add(material);
            }
            seed.add(cells);
        }
        int[] wallColumns = {4, 8, 17, 21};
        for (int column : wallColumns) {
            for (int row = 3; row <= 8; row++) if (row != 6) seed.get(row).set(column, 1);
            for (int row = 13; row <= 19; row++) if (row != 16) seed.get(row).set(column, 1);
        }
        for (int column = 3; column <= 6; column++) seed.get(10).set(column, 1);
        for (int column = 19; column <= 22; column++) seed.get(10).set(column, 1);
        for (int column = 10; column <= 15; column++) seed.get(11).set(column, 3);
        for (int row : new int[]{7, 15}) {
            seed.get(row).set(12, 2);
            seed.get(row).set(13, 2);
        }
        for (int row = 17; row <= 19; row++) {
            for (int column = 3; column <= 6; column++) seed.get(row).set(column, 4);
        }
        for (int row = 4; row <= 6; row++) {
            for (int column = 19; column <= 22; column++) seed.get(row).set(column, 4);
        }
        for (int row = 19; row <= 20; row++) {
            for (int column = 17; column <= 20; column++) seed.get(row).set(column, 5);
        }
        for (int column = 12; column <= 14; column++) seed.get(23).set(column, 1);
        seed.get(24).set(12, 1);
        seed.get(24).set(14, 1);
        return new GameMapResponse(1L, "双人合作 · 基地防线", TILE, seed);
    }
}
