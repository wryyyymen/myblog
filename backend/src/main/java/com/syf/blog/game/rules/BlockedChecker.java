package com.syf.blog.game.rules;

/**
 * 判断一个方形实体放在指定位置时会不会撞上阻挡物。
 *
 * <p>规则层刻意不实现这个判断：哪些材质挡坦克、哪些挡子弹属于碰撞模块，
 * 由另一处代码提供。规则层只负责移动算术，通过本接口向外部询问
 * "这个位置能不能站"，两边因此可以各自单独修改而不互相牵连。
 *
 * <p>坐标记的是实体左上角，占用范围是 {@code [x, x + size) × [y, y + size)}。
 */
@FunctionalInterface
public interface BlockedChecker {

    /**
     * 判断方形实体放在 (x, y) 时是否被阻挡。
     *
     * @param x 实体左上角的横坐标，单位像素
     * @param y 实体左上角的纵坐标，单位像素
     * @param size 实体边长，单位像素
     * @return 与墙体、障碍或地图外区域重叠时返回 true
     */
    boolean isBlocked(double x, double y, double size);

    /**
     * 永远不阻挡的实例，单机调试和单元测试中用作默认值。
     */
    BlockedChecker NONE = (x, y, size) -> false;
}
