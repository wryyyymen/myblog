package com.syf.blog.game.rules;

/**
 * 平面坐标，含义是实体左上角在地图中的像素位置。
 *
 * <p>用 double 而不是 int：坦克每帧移动的距离通常不是整数像素，
 * 取整会让误差不断累积，坦克走走停停看着像卡顿。
 */
public record Position(double x, double y) {
}
