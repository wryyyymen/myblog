package com.syf.blog.dto.game;

import com.syf.blog.domain.GameMap;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 地图接口返回的数据。
 * 使用 record 承载数据，Java 会自动生成构造方法和字段访问方法。
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
