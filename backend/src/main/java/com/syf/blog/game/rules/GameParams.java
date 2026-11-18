package com.syf.blog.game.rules;

/**
 * 游戏规则使用的固定参数。
 *
 * <p>这里的数值必须与前端 {@code frontend/src/game/} 下的常量保持一致。
 * 服务端是唯一权威：客户端只负责把按键状态发上来，位置、冷却和子弹数量
 * 全部由服务端按这里的值计算。改任何一个数都要同时改前端和本类，
 * 否则两个浏览器看到的画面会不一致。
 */
public final class GameParams {

    /** 坦克每秒移动的像素数，对应前端 playerMovement.ts 的 PLAYER_SPEED。 */
    public static final double PLAYER_SPEED = 72;

    /** 子弹每秒移动的像素数，对应前端 bullet.ts 的 BULLET_SPEED。 */
    public static final double BULLET_SPEED = 240;

    /** 子弹图片的边长，对应前端 bullet.ts 的 BULLET_SIZE。 */
    public static final double BULLET_SIZE = 12;

    /** 开火冷却时间，单位毫秒，对应前端 bullet.ts 的 FIRE_COOLDOWN_MS。 */
    public static final long FIRE_COOLDOWN_MILLIS = 300L;

    /**
     * 同一玩家场上同时存在的子弹上限，对应前端 MAX_BULLETS_PER_PLAYER。
     *
     * <p>改这个数必须同时改前端 bullet.ts，否则联机时客户端预测会被服务端否决。
     */
    public static final int MAX_BULLETS_PER_PLAYER = 3;

    /** 一格地图的边长，对应数据库 game_map 表的 tile_size。 */
    public static final double DEFAULT_TILE_SIZE = 24;

    /**
     * 单帧最多推进的时间，单位秒，对应前端 MAX_STEP_SECONDS。
     *
     * <p>服务端按固定步长推进，正常不会触发这个上限；
     * 保留它是为了防止某一 tick 因线程调度延迟过长而让坦克瞬移穿墙。
     */
    public static final double MAX_STEP_SECONDS = 0.1;

    private GameParams() {
        // 只用来放常量，不允许创建实例
    }
}
