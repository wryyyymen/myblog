package com.syf.blog.controller;

import com.syf.blog.dto.game.GameLevelResponse;
import com.syf.blog.service.game.GameLevelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lombok 生成只接收 GameLevelService 的构造方法。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/game/levels")
@Tag(name = "坦克关卡", description = "单人和双人合作共用的五关配置")
public class GameLevelController {
    private final GameLevelService gameLevelService;

    @GetMapping
    @Operation(summary = "查询 PVE 关卡目录", description = "允许匿名读取，按关卡顺序返回地图特点和敌军规则。",
            responses = @ApiResponse(responseCode = "200", description = "返回五个关卡摘要"))
    public List<GameLevelResponse> listLevels() {
        return gameLevelService.listLevels();
    }
}
