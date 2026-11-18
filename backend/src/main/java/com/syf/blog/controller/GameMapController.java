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

/**
 * 游戏地图 HTTP 查询入口，负责路径参数绑定、正数校验和接口文档。
 *
 * <p>地图允许匿名读取；数据库查询及响应对象转换交给地图业务服务。
 */
@Tag(name = "游戏地图",description = "查询地图信息与初始布局")
@RestController
@RequestMapping("/api/game/maps")
public class GameMapController {

    private final GameMapService gameMapService;

    /**
     * 通过构造方法注入地图业务服务。
     *
     * @param gameMapService 查询地图并转换响应对象的业务服务
     */
    public GameMapController(GameMapService gameMapService){
        this.gameMapService = gameMapService;
    }

    /**
     * 处理 GET /api/game/maps/{id}，按地图 ID 返回初始地图数据，允许匿名访问。
     *
     * <p>ID 格式错误或不大于零时，由请求参数绑定或校验流程返回 HTTP 400；
     * 查询成功返回 HTTP 200，找不到地图时由统一异常处理返回 HTTP 404。
     *
     * @param id 大于零的地图主键，来自 URL 路径
     * @return 地图 ID、名称、格子尺寸及二维初始布局矩阵
     * @throws com.syf.blog.exception.BusinessException 地图不存在时抛出，携带 HTTP 404 状态
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
