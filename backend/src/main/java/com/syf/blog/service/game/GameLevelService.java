package com.syf.blog.service.game;

import com.syf.blog.dto.game.GameLevelResponse;
import com.syf.blog.game.map.GameLevelCatalog;
import com.syf.blog.game.map.GameLevelDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 负责将只读关卡配置转换成 HTTP 返回对象，不查询数据库。 */
@Service
public class GameLevelService {
    public List<GameLevelResponse> listLevels() {
        List<GameLevelResponse> responses = new ArrayList<GameLevelResponse>();
        for (GameLevelDefinition level : GameLevelCatalog.getDefault().getLevels()) {
            GameLevelResponse response = new GameLevelResponse(level.getId(), level.getName(),
                    level.getDescription(), level.getDifficulty(), level.getEnemyTotal(),
                    level.getMaxAliveEnemies(), level.getEnemySpeed(), level.getSpawnIntervalMillis());
            responses.add(response);
        }
        return responses;
    }
}
