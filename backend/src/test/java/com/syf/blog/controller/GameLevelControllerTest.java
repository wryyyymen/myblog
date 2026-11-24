package com.syf.blog.controller;

import com.syf.blog.service.game.GameLevelService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 MVC 测试只装配目录入口和真实配置服务，不启动数据库。 */
class GameLevelControllerTest {
    @Test
    void anonymousCatalogRequestReturnsFiveOrderedLevelSummaries() throws Exception {
        GameLevelService service = new GameLevelService();
        GameLevelController controller = new GameLevelController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/game/levels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("基地防线"))
                .andExpect(jsonPath("$[0].difficulty").value("入门"))
                .andExpect(jsonPath("$[4].id").value(5))
                .andExpect(jsonPath("$[4].enemyTotal").value(16))
                .andExpect(jsonPath("$[4].maxAliveEnemies").value(5))
                .andExpect(jsonPath("$[4].enemySpeed").value(56))
                .andExpect(jsonPath("$[4].spawnIntervalMillis").value(1800))
                .andExpect(jsonPath("$[0].rows").doesNotExist());
    }
}
