package com.syf.blog.game.rules;

/**
 * 移动方向，与玩家输入消息中的 direction 编号一一对应。
 *
 * <p>0 上、1 下、2 左、3 右，和前端 keyboardInput.ts、bullet.ts 用的是同一套编号。
 * 客户端在 {@code player:input} 里传的是数字，服务端统一转成枚举再参与计算，
 * 避免在多个方法里各写一份 if 判断方向。
 */
public enum Direction {

    /** 向上，y 减小。 */
    UP(0, 0, -1),

    /** 向下，y 增大。 */
    DOWN(1, 0, 1),

    /** 向左，x 减小。 */
    LEFT(2, -1, 0),

    /** 向右，x 增大。 */
    RIGHT(3, 1, 0);

    /** 协议中使用的方向编号。 */
    private final int code;

    /** 横坐标的单位位移，取值 -1、0 或 1。 */
    private final int dx;

    /** 纵坐标的单位位移，取值 -1、0 或 1。 */
    private final int dy;

    Direction(int code, int dx, int dy) {
        this.code = code;
        this.dx = dx;
        this.dy = dy;
    }

    /**
     * 返回协议中的方向编号。
     *
     * @return 0 到 3 之间的整数
     */
    public int code() {
        return code;
    }

    /**
     * 返回横坐标的单位位移。
     *
     * @return -1、0 或 1
     */
    public int dx() {
        return dx;
    }

    /**
     * 返回纵坐标的单位位移。
     *
     * @return -1、0 或 1
     */
    public int dy() {
        return dy;
    }

    /**
     * 把客户端传来的方向编号转成枚举。
     *
     * <p>消息入口先用本方法校验，非法编号直接拒绝，
     * 不让一个乱传的数字流进规则计算里。
     *
     * @param code 协议中的方向编号
     * @return 对应的方向
     * @throws IllegalArgumentException 编号不在 0 到 3 之间时抛出
     */
    public static Direction of(int code) {
        for (Direction direction : values()) {
            if (direction.code == code) {
                return direction;
            }
        }
        throw new IllegalArgumentException("未知的方向编号：" + code);
    }
}
