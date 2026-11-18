package com.syf.blog.game.rules;

/**
 * 一颗飞行中的子弹。
 *
 * <p>坐标记的是子弹左上角，和前端 {@code BulletData} 一致。
 * 方向在生成时确定，之后不再改变，因此这里用 final 字段表达这一点。
 *
 * @param id 子弹编号，由服务端分配，同一局内唯一
 * @param x 左上角横坐标，单位像素
 * @param y 左上角纵坐标，单位像素
 * @param direction 飞行方向
 * @param ownerId 发射者的用户标识，用于区分敌我和统计命中
 */
public record BulletState(long id, double x, double y, Direction direction, String ownerId) {
}
