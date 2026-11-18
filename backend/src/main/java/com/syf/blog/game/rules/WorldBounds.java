package com.syf.blog.game.rules;

/**
 * 可移动区域的宽高，通常就是本局地图的世界尺寸。
 *
 * <p>服务端不读画布尺寸：画布多大是浏览器决定的，同一局地图在不同设备上
 * 可能显示成不同大小。服务端只认地图本身的行数、列数乘格子边长。
 *
 * @param width 世界宽度，单位像素
 * @param height 世界高度，单位像素
 */
public record WorldBounds(double width, double height) {
}
