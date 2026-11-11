package com.syf.blog.dto.game;

import com.syf.blog.domain.GameMap;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 地图接口返回的数据。
 *
 * <p>使用 record 承载数据，Java 自动生成构造方法和 id()、name() 等字段访问方法。
 *
 * @param id 地图主键
 * @param name 地图名称
 * @param tileSize 单个格子的像素边长，供前端计算地图和素材的绘制尺寸
 * @param seed 二维材质矩阵，外层为行、内层为列；0 空地、1 砖墙、2 钢墙、3 河流、4 树林、5 冰面
 */
@Schema(description = "游戏地图")
public record GameMapResponse(

        @Schema(description = "地图ID", example = "1")
        Long id,

        @Schema(description = "地图名称", example = "材质测试地图")
        String name,

        @Schema(description = "格子边长，单位为像素", example = "24")
        Integer tileSize,

        @Schema(
                description = "二维材质矩阵：0空地、1砖墙、2钢墙、3河流、4树林、5冰面",
                example = "[[1,1,1],[1,0,1],[1,1,1]]"
        )
        List<List<Integer>> seed

) {

    /**
     * 将数据库实体转换为接口响应对象。
     *
     * <p>直接映射实体字段，不修改或深复制地图的二维材质列表。
     * 地图是否存在由业务 Service 在调用前检查。
     *
     * @param map 已查询到的地图实体，调用方应保证非空
     * @return 包含地图 ID、名称、格子边长和材质矩阵的响应
     */
    public static GameMapResponse from(GameMap map) {
        return new GameMapResponse(
                map.getId(),
                map.getName(),
                map.getTileSize(),
                map.getSeed()
        );
    }
}
