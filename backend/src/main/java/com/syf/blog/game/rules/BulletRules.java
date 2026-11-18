package com.syf.blog.game.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * 子弹规则：生成、推进、越界清理和开火许可。
 *
 * <p>与前端 {@code frontend/src/game/bullet.ts} 一一对应。
 * 前端那份用于单机试玩，本类用于服务端权威计算，两边的公式、上限和冷却
 * 必须完全相同，否则联机时客户端预测的位置会和服务端对不上。
 *
 * <p>推进与清理都返回新列表，不修改传入的列表。这样一次 tick 里
 * 可以安全地把同一份数据交给碰撞判定使用，出了 bug 也容易定位是哪一步改的。
 */
public final class BulletRules {

    private BulletRules() {
        // 工具类，不允许创建实例
    }

    /**
     * 在坦克的炮口位置生成一颗子弹。
     *
     * <p>先取坦克中心，再减去子弹半径让子弹在垂直方向上居中，
     * 最后按朝向推出半个坦克身位，让子弹出现在车体前缘。
     *
     * @param tank 发射者的左上角坐标
     * @param tankSize 发射者的边长
     * @param direction 飞行方向
     * @param ownerId 发射者标识
     * @param id 服务端分配的子弹编号
     * @return 新生成的子弹
     */
    public static BulletState spawn(
            Position tank,
            double tankSize,
            Direction direction,
            String ownerId,
            long id) {

        // 坦克中心减去子弹半径，让子弹相对坦克居中
        double x = tank.x() + tankSize / 2 - GameParams.BULLET_SIZE / 2;
        double y = tank.y() + tankSize / 2 - GameParams.BULLET_SIZE / 2;

        // 再往朝向那一侧推出去，推到车体之外
        double offset = tankSize / 2;
        x += direction.dx() * offset;
        y += direction.dy() * offset;

        return new BulletState(id, x, y, direction, ownerId);
    }

    /**
     * 按经过的时间推进所有子弹。
     *
     * <p>必须乘时间：不乘的话，帧率高的机器上子弹会飞得更快。
     *
     * @param bullets 当前子弹列表
     * @param deltaSeconds 距离上一次推进经过的秒数
     * @return 推进后的新列表，顺序与传入一致
     */
    public static List<BulletState> advance(List<BulletState> bullets, double deltaSeconds) {
        double step = GameParams.BULLET_SPEED * clampStep(deltaSeconds);

        List<BulletState> advanced = new ArrayList<>(bullets.size());
        for (BulletState bullet : bullets) {
            advanced.add(new BulletState(
                    bullet.id(),
                    bullet.x() + bullet.direction().dx() * step,
                    bullet.y() + bullet.direction().dy() * step,
                    bullet.direction(),
                    bullet.ownerId()));
        }
        return advanced;
    }

    /**
     * 移除已经完全飞出世界的子弹。
     *
     * <p>判定用"整体离开"而不是"中心离开"：子弹只要还有一部分压在画面内
     * 就继续保留，避免子弹在边缘提前消失。这一点与前端实现保持一致。
     *
     * @param bullets 当前子弹列表
     * @param bounds 世界宽高
     * @return 仍在世界范围内的子弹
     */
    public static List<BulletState> removeOutOfBounds(List<BulletState> bullets, WorldBounds bounds) {
        double half = GameParams.BULLET_SIZE / 2;

        List<BulletState> kept = new ArrayList<>(bullets.size());
        for (BulletState bullet : bullets) {
            boolean inside = bullet.x() + half >= 0
                    && bullet.x() - half <= bounds.width()
                    && bullet.y() + half >= 0
                    && bullet.y() - half <= bounds.height();

            if (inside) {
                kept.add(bullet);
            }
        }
        return kept;
    }

    /**
     * 统计某个玩家当前在场上的子弹数量。
     *
     * @param bullets 当前子弹列表
     * @param ownerId 玩家标识
     * @return 属于该玩家的子弹数
     */
    public static int countOwned(List<BulletState> bullets, String ownerId) {
        int count = 0;
        for (BulletState bullet : bullets) {
            if (bullet.ownerId().equals(ownerId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断这一次开火请求能不能真的产生子弹。
     *
     * <p>两个条件同时成立才允许：场上属于自己的子弹没到上限，且距上次开火
     * 已超过冷却时间。客户端按住空格会持续发送开火状态，服务端每 tick 都调
     * 本方法判断，因此"按住就连续射击"不需要客户端自己计时。
     *
     * @param ownedBulletCount 该玩家场上现有的子弹数
     * @param lastFireMillis 上一次成功开火的时间戳，单位毫秒
     * @param nowMillis 当前时间戳，单位毫秒
     * @return 允许开火时返回 true
     */
    public static boolean canFire(int ownedBulletCount, long lastFireMillis, long nowMillis) {
        if (ownedBulletCount >= GameParams.MAX_BULLETS_PER_PLAYER) {
            return false;
        }

        long elapsed = nowMillis - lastFireMillis;

        // 时间戳回退时 elapsed 为负，按冷却已结束处理，避免因为对时导致再也开不出火
        if (elapsed < 0) {
            return true;
        }
        return elapsed >= GameParams.FIRE_COOLDOWN_MILLIS;
    }

    /**
     * 把经过时间限制在 0 到单帧上限之间。
     *
     * @param deltaSeconds 原始经过时间
     * @return 可直接参与计算的秒数
     */
    private static double clampStep(double deltaSeconds) {
        if (deltaSeconds <= 0 || Double.isNaN(deltaSeconds)) {
            return 0;
        }
        return Math.min(deltaSeconds, GameParams.MAX_STEP_SECONDS);
    }
}
