package com.syf.blog.controller;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.service.GameMapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "游戏地图",description = "查询地图信息与初始布局")
@RestController
@RequestMapping("/api/game/maps")
public class GameMapController {

    private final GameMapService gameMapService;

    //注入地图业务服务
    public GameMapController(GameMapService gameMapService){
        this.gameMapService = gameMapService;
    }

    /**
     * 根据地图 ID 查询地图，允许匿名访问。
     */
    @Operation(
            summary = "查询地图",
            description = "允许匿名访问，返回地图名称、格子尺寸和二维材质矩阵。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "查询成功"),
                    @ApiResponse(responseCode = "400", description = "地图ID格式错误或不大于0"),
                    @ApiResponse(responseCode = "404", description = "地图不存在")
            }
    )
    @GetMapping("/{id}")
    public GameMapResponse getById(
            @Parameter(description = "地图ID", example = "1")
            @PathVariable("id")
            @Positive(message = "地图ID必须大于0")
            Long id
    ) {
        // 交给 Service 查询并转换响应对象
        return gameMapService.getById(id);
    }
}
