package com.syf.blog.game.engine;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.game.runtime.BulletState;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.PlayerInput;
import com.syf.blog.game.runtime.TankState;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 服务端射击、子弹碰撞与结算规则；不读取数据库或发送网络消息。 */
public class GameCombatEngine {

    public static final int INITIAL_HP = 3;
    public static final double BULLET_SPEED = 360.0;
    public static final double BULLET_RADIUS = 3.0;
    public static final long FIRE_COOLDOWN_MILLIS = 350L;
    public static final long MATCH_DURATION_MILLIS = 180_000L;
    public static final int MAX_BULLETS_PER_TANK = 4;
    public static final long SPAWN_PROTECTION_MILLIS = 3_000L;
    public static final long HIT_PROTECTION_MILLIS = 900L;

    private static final long FIRE_COOLDOWN_TICKS = FIRE_COOLDOWN_MILLIS / GameMovementEngine.STEP_MILLIS;
    private static final long MATCH_DURATION_TICKS = MATCH_DURATION_MILLIS / GameMovementEngine.STEP_MILLIS;
    private static final long HIT_PROTECTION_TICKS = HIT_PROTECTION_MILLIS / GameMovementEngine.STEP_MILLIS;
    private static final double EPSILON = 0.00000001;

    /** 调用前由移动引擎推进一个 tick；结束局重复调用不会再生成伤害或结算。 */
    public void step(GameMatch match, long nowMillis) {
        if (match.isFinished()) {
            return;
        }

        fireBullets(match, nowMillis);
        Map<Long, Integer> damageByUserId = new HashMap<Long, Integer>();
        advanceBullets(match, damageByUserId);

        // 同一 tick 的全部子弹先判断命中，再统一扣血，允许双方同时被击毁。
        for (TankState tank : match.allTanks()) {
            Integer damage = damageByUserId.get(tank.getUserId());
            if (damage != null) {
                boolean cooperativePlayer = isCooperativePlayer(match, tank);
                if (cooperativePlayer) {
                    // 将同 tick 的命中合并为一次受击，等价于首击立即开启保护。
                    damage = 1;
                }
                int remainingHp = Math.max(0, tank.getHp() - damage);
                tank.setHp(remainingHp);
                if (cooperativePlayer) {
                    long shieldUntilTick = 0L;
                    if (remainingHp > 0) {
                        shieldUntilTick = match.getTick() + HIT_PROTECTION_TICKS;
                    }
                    tank.setShieldUntilTick(shieldUntilTick);
                    System.out.println("[GameCombatEngine.step] 合作玩家受击，userId = " + tank.getUserId()
                            + "，hp = " + remainingHp + "，保护结束 tick = " + shieldUntilTick);
                }
            }
        }
        finishIfNecessary(match);
    }

    private void fireBullets(GameMatch match, long nowMillis) {
        for (TankState tank : match.allTanks()) {
            if (tank.getHp() <= 0 || match.getTick() < tank.getNextFireTick()) {
                continue;
            }
            PlayerInput input = match.getInputs().get(tank.getUserId());
            if (input == null || !input.isFireHeld() || input.getSeq() < tank.getLastInputSeq()) {
                continue;
            }
            long inputAge = nowMillis - input.getReceivedAtMillis();
            if (inputAge < 0L || inputAge > GameMovementEngine.INPUT_TIMEOUT_MILLIS) {
                continue;
            }

            int bulletCount = 0;
            for (BulletState bullet : match.getBullets()) {
                if (bullet.getOwnerUserId().equals(tank.getUserId())) {
                    bulletCount++;
                }
            }
            if (bulletCount >= MAX_BULLETS_PER_TANK) {
                continue;
            }

            double x = tank.getX() + tank.getSize() / 2.0;
            double y = tank.getY() + tank.getSize() / 2.0;
            double muzzleOffset = tank.getSize() / 2.0 + BULLET_RADIUS;
            int direction = tank.getDirection();
            if (direction == 0) {
                y = y - muzzleOffset;
            } else if (direction == 1) {
                y = y + muzzleOffset;
            } else if (direction == 2) {
                x = x - muzzleOffset;
            } else if (direction == 3) {
                x = x + muzzleOffset;
            } else {
                continue;
            }

            long bulletId = match.getNextBulletId();
            BulletState bullet = new BulletState(bulletId, tank.getUserId(), x, y, direction);
            match.getBullets().add(bullet);
            match.setNextBulletId(bulletId + 1L);
            long cooldown = tank.getUserId() < 0L ? 24L : FIRE_COOLDOWN_TICKS;
            tank.setNextFireTick(match.getTick() + cooldown);
        }
    }

    private void advanceBullets(GameMatch match, Map<Long, Integer> damageByUserId) {
        double distance = BULLET_SPEED * GameMovementEngine.STEP_MILLIS / 1000.0;
        Iterator<BulletState> iterator = match.getBullets().iterator();
        while (iterator.hasNext()) {
            BulletState bullet = iterator.next();
            BulletHit hit = findNearestHit(match, bullet, distance);
            if (hit != null) {
                if (hit.getTank() != null) {
                    TankState target = hit.getTank();
                    boolean protectedPlayer = isCooperativePlayer(match, target)
                            && match.getTick() < target.getShieldUntilTick();
                    if (!protectedPlayer) {
                        Long userId = target.getUserId();
                        Integer previousDamage = damageByUserId.get(userId);
                        int damage = previousDamage == null ? 1 : previousDamage + 1;
                        damageByUserId.put(userId, damage);
                    }
                } else if (hit.getRow() == -2) {
                    match.getBase().setHp(0);
                } else if (hit.getRow() >= 0) {
                    int material = match.getMap().seed().get(hit.getRow()).get(hit.getColumn());
                    if (material == 1) {
                        int columns = match.getMap().seed().get(0).size();
                        int cellIndex = hit.getRow() * columns + hit.getColumn();
                        match.getDestroyedCells().add(cellIndex);
                    }
                }
                iterator.remove();
                continue;
            }

            if (bullet.getDirection() == 0) {
                bullet.setY(bullet.getY() - distance);
            } else if (bullet.getDirection() == 1) {
                bullet.setY(bullet.getY() + distance);
            } else if (bullet.getDirection() == 2) {
                bullet.setX(bullet.getX() - distance);
            } else {
                bullet.setX(bullet.getX() + distance);
            }
        }
    }

    /** 只有合作模式的人类玩家有出生/受击保护，敌军和旧 PVP 规则保持原样。 */
    private boolean isCooperativePlayer(GameMatch match, TankState tank) {
        return "COOP".equals(match.getMode()) && tank.getUserId() > 0L;
    }

    /** 对整段路径检测最近碰撞，而非只检查终点，避免高速子弹跳过薄墙或坦克。 */
    private BulletHit findNearestHit(GameMatch match, BulletState bullet, double distance) {
        GameMapResponse map = match.getMap();
        int tileSize = map.tileSize();
        int rows = map.seed().size();
        int columns = map.seed().get(0).size();
        double endX = bullet.getX();
        double endY = bullet.getY();
        if (bullet.getDirection() == 0) {
            endY = endY - distance;
        } else if (bullet.getDirection() == 1) {
            endY = endY + distance;
        } else if (bullet.getDirection() == 2) {
            endX = endX - distance;
        } else {
            endX = endX + distance;
        }

        int firstRow = Math.max(0, (int) Math.floor((Math.min(bullet.getY(), endY) - BULLET_RADIUS) / tileSize));
        int lastRow = Math.min(rows - 1,
                (int) Math.floor((Math.max(bullet.getY(), endY) + BULLET_RADIUS) / tileSize));
        int firstColumn = Math.max(0, (int) Math.floor((Math.min(bullet.getX(), endX) - BULLET_RADIUS) / tileSize));
        int lastColumn = Math.min(columns - 1,
                (int) Math.floor((Math.max(bullet.getX(), endX) + BULLET_RADIUS) / tileSize));

        BulletHit nearest = null;
        // 墙体先比较，同一距离时墙体优先，防止贴墙的坦克被隔墙击中。
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                int material = map.seed().get(row).get(column);
                int cellIndex = row * columns + column;
                if (material != 1 && material != 2 || material == 1 && match.getDestroyedCells().contains(cellIndex)) {
                    continue;
                }
                double hitDistance = distanceToRectangle(bullet, column * tileSize,
                        row * tileSize, tileSize, tileSize);
                if (isNearer(hitDistance, nearest, distance)) {
                    nearest = new BulletHit(hitDistance, row, column, null);
                }
            }
        }

        for (TankState tank : match.allTanks()) {
            if (tank.getHp() <= 0 || tank.getUserId().equals(bullet.getOwnerUserId())) {
                continue;
            }
            // 合作模式同阵营互不造成伤害，子弹可以穿过队友。
            if ("COOP".equals(match.getMode())
                    && (tank.getUserId() > 0L) == (bullet.getOwnerUserId() > 0L)) {
                continue;
            }
            double hitDistance = distanceToRectangle(bullet, tank.getX(), tank.getY(), tank.getSize(), tank.getSize());
            if (isNearer(hitDistance, nearest, distance)) {
                nearest = new BulletHit(hitDistance, -1, -1, tank);
            }
        }

        if (match.getBase() != null && match.getBase().getHp() > 0 && bullet.getOwnerUserId() < 0L) {
            double hitDistance = distanceToRectangle(bullet, match.getBase().getX(), match.getBase().getY(),
                    match.getBase().getSize(), match.getBase().getSize());
            if (isNearer(hitDistance, nearest, distance)) {
                nearest = new BulletHit(hitDistance, -2, -1, null);
            }
        }

        double boundaryDistance = distanceToBoundary(bullet, columns * tileSize, rows * tileSize);
        if (isNearer(boundaryDistance, nearest, distance)) {
            nearest = new BulletHit(boundaryDistance, -1, -1, null);
        }
        return nearest;
    }

    private boolean isNearer(double distance, BulletHit previous, double requestedDistance) {
        return distance <= requestedDistance + EPSILON
                && (previous == null || distance < previous.getDistance() - EPSILON);
    }

    /** 将障碍沿四周扩展子弹半径后，求子弹中心沿当前方向到矩形的距离。 */
    private double distanceToRectangle(BulletState bullet, double x, double y, double width, double height) {
        double left = x - BULLET_RADIUS;
        double right = x + width + BULLET_RADIUS;
        double top = y - BULLET_RADIUS;
        double bottom = y + height + BULLET_RADIUS;
        if (bullet.getDirection() == 0) {
            if (bullet.getX() < left || bullet.getX() > right || bullet.getY() < top) return Double.POSITIVE_INFINITY;
            return Math.max(0.0, bullet.getY() - bottom);
        }
        if (bullet.getDirection() == 1) {
            if (bullet.getX() < left || bullet.getX() > right || bullet.getY() > bottom) return Double.POSITIVE_INFINITY;
            return Math.max(0.0, top - bullet.getY());
        }
        if (bullet.getDirection() == 2) {
            if (bullet.getY() < top || bullet.getY() > bottom || bullet.getX() < left) return Double.POSITIVE_INFINITY;
            return Math.max(0.0, bullet.getX() - right);
        }
        if (bullet.getY() < top || bullet.getY() > bottom || bullet.getX() > right) return Double.POSITIVE_INFINITY;
        return Math.max(0.0, left - bullet.getX());
    }

    private double distanceToBoundary(BulletState bullet, double width, double height) {
        if (bullet.getX() < BULLET_RADIUS || bullet.getX() > width - BULLET_RADIUS
                || bullet.getY() < BULLET_RADIUS || bullet.getY() > height - BULLET_RADIUS) {
            return 0.0;
        }
        if (bullet.getDirection() == 0) return bullet.getY() - BULLET_RADIUS;
        if (bullet.getDirection() == 1) return height - BULLET_RADIUS - bullet.getY();
        if (bullet.getDirection() == 2) return bullet.getX() - BULLET_RADIUS;
        return width - BULLET_RADIUS - bullet.getX();
    }

    private void finishIfNecessary(GameMatch match) {
        if ("COOP".equals(match.getMode())) {
            finishCoopIfNecessary(match);
            return;
        }
        TankState survivor = null;
        int aliveCount = 0;
        for (TankState tank : match.getTanks()) {
            if (tank.getHp() > 0) {
                survivor = tank;
                aliveCount++;
            }
        }
        if (aliveCount == 0) {
            finish(match, null, "DRAW");
        } else if (aliveCount == 1) {
            finish(match, survivor.getUserId(), "DESTROYED");
        } else if (match.getTick() >= MATCH_DURATION_TICKS) {
            finish(match, null, "TIME_LIMIT");
        }
    }

    private void finishCoopIfNecessary(GameMatch match) {
        int alivePlayers = 0;
        for (TankState tank : match.getTanks()) {
            if (tank.getHp() > 0) alivePlayers++;
        }
        int defeated = 0;
        for (TankState enemy : match.getEnemies()) {
            if (enemy.getHp() <= 0) defeated++;
        }
        String result = null;
        String reason = null;
        if (match.getBase().getHp() <= 0) {
            result = "DEFEAT";
            reason = "BASE_DESTROYED";
        } else if (alivePlayers == 0) {
            result = "DEFEAT";
            reason = "TEAM_DESTROYED";
        } else if (defeated == GameCoopEngine.ENEMY_TOTAL) {
            result = "VICTORY";
            reason = "ENEMIES_CLEARED";
        } else if (match.getTick() >= MATCH_DURATION_TICKS) {
            result = "DEFEAT";
            reason = "TIME_LIMIT";
        }
        if (result != null) {
            match.setTeamResult(result);
            finish(match, null, reason);
        }
    }

    private void finish(GameMatch match, Long winnerUserId, String reason) {
        match.setFinished(true);
        match.setWinnerUserId(winnerUserId);
        match.setEndReason(reason);
        match.getInputs().clear();
        for (TankState tank : match.getTanks()) {
            tank.setShieldUntilTick(0L);
        }
        System.out.println("[GameCombatEngine.finish] matchId = " + match.getMatchId()
                + "，reason = " + reason + "，winnerUserId = " + winnerUserId);
    }

    /** row=-1 且 tank=null 表示地图边界。构造参数为距离、行、列、目标坦克。 */
    @Getter
    @RequiredArgsConstructor
    private static class BulletHit {
        private final double distance;
        private final int row;
        private final int column;
        private final TankState tank;
    }
}
