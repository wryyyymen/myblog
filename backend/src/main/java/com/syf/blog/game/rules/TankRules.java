package com.syf.blog.game.rules;

/**
 * 坦克移动规则。
 *
 * <p>与前端 {@code frontend/src/game/playerMovement.ts} 的 movePlayer 一一对应：
 * 相同的常量、相同的边界钳制、相同的横纵分开判定。两端算出同一个位置，
 * 验收标准 AC-1（两个浏览器坐标差不超过 2 像素）才有可能成立。
 *
 * <p>本类只做算术，不保存状态、不访问数据库、不发送消息，
 * 因此可以直接用单元测试覆盖全部分支。
 */
public final class TankRules {

    private TankRules() {
        // 工具类，不允许创建实例
    }

    /**
     * 按方向和经过的时间推进坦克位置。
     *
     * <p>横向和纵向分开判定：贴着竖墙往下走时，横向被挡住但纵向还能动。
     * 如果合成一次整体判定，坦克会整辆卡死在墙边。
     *
     * @param current 当前坐标，记的是坦克左上角
     * @param direction 移动方向；null 表示客户端已松键，本帧不移动
     * @param deltaSeconds 距离上一次推进经过的秒数
     * @param bounds 世界宽高
     * @param tankSize 坦克边长，通常等于地图的格子边长
     * @param checker 阻挡判定，由碰撞模块提供
     * @return 计算后的新坐标，保证不越界、不与阻挡物重叠
     */
    public static Position move(
            Position current,
            Direction direction,
            double deltaSeconds,
            WorldBounds bounds,
            double tankSize,
            BlockedChecker checker) {

        // 松键时方向为空，坦克停在原地，朝向由调用方单独保存
        if (direction == null) {
            return current;
        }

        // 这一帧走的距离 = 每秒速度 × 经过时间。
        // 时间必须先截断，否则一个卡顿的 tick 会让坦克穿过薄墙。
        double step = GameParams.PLAYER_SPEED * clampStep(deltaSeconds);

        // 坐标记的是左上角，右下角不能超出世界，所以上限要减去坦克自身尺寸。
        // 世界比坦克还小时上限取 0，避免出现负数导致坦克被卡在界外。
        double maxX = Math.max(0, bounds.width() - tankSize);
        double maxY = Math.max(0, bounds.height() - tankSize);

        double nextX = clamp(current.x() + direction.dx() * step, 0, maxX);
        if (checker.isBlocked(nextX, current.y(), tankSize)) {
            // 撞墙就保持原横坐标，横向这一帧等于没走
            nextX = current.x();
        }

        double nextY = clamp(current.y() + direction.dy() * step, 0, maxY);
        // 纵向判定用已经确定的 nextX，斜着撞墙角时才不会挤进墙里
        if (checker.isBlocked(nextX, nextY, tankSize)) {
            nextY = current.y();
        }

        return new Position(nextX, nextY);
    }

    /**
     * 把经过时间限制在 0 到单帧上限之间。
     *
     * <p>第一帧或时钟异常可能传入 0 或负数，按 0 处理，坦克不动。
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

    /**
     * 把数值限制在 min 与 max 之间。
     *
     * @param value 原始数值
     * @param min 下限
     * @param max 上限
     * @return 落在区间内的数值
     */
    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
