package com.syf.blog.game.engine;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.runtime.BaseState;
import com.syf.blog.game.runtime.EnemyBrain;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.PlayerInput;
import com.syf.blog.game.runtime.TankState;
import com.syf.blog.game.map.GameLevelCatalog;
import com.syf.blog.game.map.GameLevelDefinition;
import com.syf.blog.game.map.GameMapCell;
import org.springframework.http.HttpStatus;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

/** 合作关卡、分批出兵和 AI 决策。只操作本局内存，不查询数据库。 */
public class GameCoopEngine {
    private static final int TILE = 24;
    private static final int BOARD = 26;
    private static final int[] ROW_DELTA = {-1, 1, 0, 0};
    private static final int[] COLUMN_DELTA = {0, 0, -1, 1};
    private final GameMovementEngine movement = new GameMovementEngine();

    /** 复用单人关卡的布局规则，两名玩家出生在基地两侧。 */
    public GameMatch createMatch(String roomId, String matchId, List<Long> userIds) {
        GameLevelDefinition level = GameLevelCatalog.getDefault().getById(1);
        return createMatch(roomId, matchId, userIds, level);
    }

    /** 地图、出生点和敌军规则固定到同一份本局模板。 */
    public GameMatch createMatch(String roomId, String matchId, List<Long> userIds, GameLevelDefinition level) {
        GameMapResponse map = level.createMap();
        return createMatch(roomId, matchId, userIds, level, map);
    }

    /** 安装调用方已经读取的地图副本，不在此处重新创建地图。 */
    public GameMatch createMatch(String roomId, String matchId, List<Long> userIds,
                                 GameLevelDefinition level, GameMapResponse sourceMap) {
        requireLevelMap(level, sourceMap);
        // 使用共同入口验证玩家和地图，再重新安排合作出生点。
        GameMatch match = movement.createMatch(roomId, matchId, sourceMap, userIds);
        match.setMode("COOP");
        match.setLevel(level);
        GameMapCell firstSpawn = level.getPlayerSpawns().get(0);
        GameMapCell secondSpawn = level.getPlayerSpawns().get(1);
        place(match.getTanks().get(0), firstSpawn.getRow(), firstSpawn.getColumn());
        place(match.getTanks().get(1), secondSpawn.getRow(), secondSpawn.getColumn());
        match.getTanks().get(0).setDirection(0);
        match.getTanks().get(1).setDirection(0);
        // 模拟从 tick=0 开始；加载等待不消耗保护，真正开局后持续 3 秒。
        long spawnProtectionTicks = GameCombatEngine.SPAWN_PROTECTION_MILLIS / GameMovementEngine.STEP_MILLIS;
        for (TankState player : match.getTanks()) {
            player.setShieldUntilTick(spawnProtectionTicks);
        }
        BaseState base = new BaseState(level.getBase().getColumn() * (double) TILE,
                level.getBase().getRow() * (double) TILE, TILE);
        match.setBase(base);
        return match;
    }

    /** 地图布局和敌军规则必须属于同一关，避免安装时悄悄丢弃不一致的地图。 */
    private void requireLevelMap(GameLevelDefinition level, GameMapResponse map) {
        if (level == null || map == null || map.id() == null || map.id() != level.getId()
                || map.tileSize() == null || map.tileSize() != level.getTileSize()
                || map.seed() == null || map.seed().size() != level.getRows().size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "合作地图与关卡模板不一致");
        }
        for (int row = 0; row < level.getRows().size(); row++) {
            String expectedRow = level.getRows().get(row);
            List<Integer> actualRow = map.seed().get(row);
            if (actualRow == null || actualRow.size() != expectedRow.length()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "合作地图与关卡模板不一致");
            }
            for (int column = 0; column < expectedRow.length(); column++) {
                Integer material = actualRow.get(column);
                if (material == null || material != expectedRow.charAt(column) - '0') {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "合作地图与关卡模板不一致");
                }
            }
        }
    }

    /** 在人类移动和子弹更新前调用一次；AI 速度从本关配置读取。 */
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
        GameLevelDefinition level = match.getLevel();
        if (match.getEnemySpawned() >= level.getEnemyTotal() || match.getTick() < match.getNextSpawnTick()) return;
        int alive = 0;
        for (TankState enemy : match.getEnemies()) {
            if (enemy.getHp() > 0) alive++;
        }
        if (alive >= level.getMaxAliveEnemies()) return;
        List<GameMapCell> spawns = level.getEnemySpawns();
        int first = match.getEnemySpawned() % spawns.size();
        for (int attempt = 0; attempt < spawns.size(); attempt++) {
            GameMapCell spawn = spawns.get((first + attempt) % spawns.size());
            long identity = -(match.getEnemySpawned() + 1L);
            TankState enemy = new TankState(identity, TILE * 0.8);
            place(enemy, spawn.getRow(), spawn.getColumn());
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
            match.setNextSpawnTick(match.getTick() + level.getSpawnIntervalMillis() / GameMovementEngine.STEP_MILLIS);
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
        double stepDistance = match.getLevel().getEnemySpeed() * GameMovementEngine.STEP_MILLIS / 1000.0;
        double moved = movement.moveTank(match, enemy, direction, Math.min(stepDistance, distance));
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
        int targetRow = match.getLevel().getBase().getRow() - 2;
        int targetColumn = match.getLevel().getBase().getColumn();
        int baseCell = match.getLevel().getBase().getRow() * BOARD + targetColumn;
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
                if (previous[next] >= 0 || material == 2 || material == 3 || next == baseCell) continue;
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

    /** 兼容原有调用，默认返回第一关的独立地图副本。 */
    public GameMapResponse createMap() {
        GameLevelDefinition level = GameLevelCatalog.getDefault().getById(1);
        return level.createMap();
    }
}
