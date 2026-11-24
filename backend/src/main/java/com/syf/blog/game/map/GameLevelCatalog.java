package com.syf.blog.game.map;

import com.syf.blog.exception.BusinessException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 单人和合作关卡的配置入口。唯一数据源是 game/maps/levels.json。
 * 首次使用时读取一次，后续只读取不可变模板，不访问数据库。
 */
public class GameLevelCatalog {
    private static final int BOARD_SIZE = 26;
    private static final int LEVEL_COUNT = 5;
    private final List<GameLevelDefinition> levels;

    /** 这里包含配置读取和校验，因此保留显式构造方法。调用者负责关闭输入流。 */
    GameLevelCatalog(InputStream input) {
        if (input == null) {
            throw new IllegalStateException("缺少关卡配置 game/maps/levels.json");
        }
        JsonMapper mapper = new JsonMapper();
        JsonNode root = mapper.readTree(input);
        require(root != null && root.isObject(), "关卡配置必须是 JSON 对象");
        require(integer(root, "version", 1, 1) == 1, "关卡配置版本不正确");
        int tileSize = integer(root, "tileSize", 24, 24);
        GameMapCell base = cell(root.get("base"));
        List<GameMapCell> playerSpawns = cells(root.get("playerSpawns"), 2);
        List<GameMapCell> enemySpawns = cells(root.get("enemySpawns"), 3);
        requireDistinctSpawns(base, playerSpawns, enemySpawns);
        JsonNode levelNodes = root.get("levels");
        require(levelNodes != null && levelNodes.isArray() && levelNodes.size() == LEVEL_COUNT,
                "关卡配置必须包含 5 个关卡");
        List<GameLevelDefinition> definitions = new ArrayList<GameLevelDefinition>();
        for (int index = 0; index < levelNodes.size(); index++) {
            JsonNode level = levelNodes.get(index);
            int id = integer(level, "id", 1, LEVEL_COUNT);
            require(id == index + 1, "关卡 ID 必须按 1 到 5 排列");
            String name = text(level, "name");
            String description = text(level, "description");
            String difficulty = text(level, "difficulty");
            int enemyTotal = integer(level, "enemyTotal", 1, 30);
            int maxAliveEnemies = integer(level, "maxAliveEnemies", 1, 8);
            require(maxAliveEnemies <= enemyTotal, "同时出现的敌人数不能超过敌人总数");
            JsonNode speed = level.get("enemySpeed");
            require(speed != null && speed.isNumber() && Double.isFinite(speed.asDouble())
                    && speed.asDouble() > 0.0 && speed.asDouble() <= 120.0, "敌人速度必须在 0 到 120 之间");
            long spawnIntervalMillis = integer(level, "spawnIntervalMillis", 50, 10_000);
            require(spawnIntervalMillis % 50L == 0L, "出兵间隔必须是 50 毫秒的整数倍");
            List<String> rows = rows(level.get("rows"));
            requireEmpty(rows, base);
            for (GameMapCell spawn : playerSpawns) {
                requireEmpty(rows, spawn);
            }
            for (GameMapCell spawn : enemySpawns) {
                requireEmpty(rows, spawn);
            }
            GameLevelDefinition definition = new GameLevelDefinition(id, name, description, difficulty,
                    enemyTotal, maxAliveEnemies, speed.asDouble(), spawnIntervalMillis, tileSize,
                    base, playerSpawns, enemySpawns, rows);
            definitions.add(definition);
        }
        levels = List.copyOf(definitions);
        System.out.println("[GameLevelCatalog] 已加载关卡数量 = " + levels.size());
    }

    /** 静态持有者只创建一个目录，同时兼容纯 Java 引擎测试与 Spring 应用。 */
    private static class DefaultCatalog {
        private static final GameLevelCatalog INSTANCE = loadDefault();
    }

    public static GameLevelCatalog getDefault() {
        return DefaultCatalog.INSTANCE;
    }

    private static GameLevelCatalog loadDefault() {
        try (InputStream input = GameLevelCatalog.class.getResourceAsStream("/game/maps/levels.json")) {
            return new GameLevelCatalog(input);
        } catch (IOException exception) {
            throw new IllegalStateException("关卡配置读取失败", exception);
        }
    }

    public List<GameLevelDefinition> getLevels() {
        return levels;
    }

    /** 请求未携带 levelId 时选择第一关；非法 ID 在房间开始加载前拒绝。 */
    public GameLevelDefinition getById(Integer levelId) {
        int selectedId = levelId == null ? 1 : levelId;
        if (selectedId < 1 || selectedId > levels.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "关卡不存在，请选择 1 到 5 关");
        }
        return levels.get(selectedId - 1);
    }

    private static int integer(JsonNode object, String field, int minimum, int maximum) {
        require(object != null && object.isObject(), "关卡字段必须位于 JSON 对象中");
        JsonNode value = object.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), field + " 必须是整数");
        int number = value.asInt();
        require(number >= minimum && number <= maximum, field + " 超出允许范围");
        return number;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        require(value != null && value.isString() && !value.asText().isBlank(), field + " 不能为空");
        return value.asText();
    }

    private static GameMapCell cell(JsonNode value) {
        int row = integer(value, "row", 1, BOARD_SIZE - 2);
        int column = integer(value, "column", 1, BOARD_SIZE - 2);
        return new GameMapCell(row, column);
    }

    private static List<GameMapCell> cells(JsonNode value, int expectedSize) {
        require(value != null && value.isArray() && value.size() == expectedSize, "出生点数量不正确");
        List<GameMapCell> result = new ArrayList<GameMapCell>();
        for (JsonNode node : value) {
            result.add(cell(node));
        }
        return List.copyOf(result);
    }

    private static List<String> rows(JsonNode value) {
        require(value != null && value.isArray() && value.size() == BOARD_SIZE, "地图必须包含 26 行");
        List<String> result = new ArrayList<String>();
        for (int rowIndex = 0; rowIndex < value.size(); rowIndex++) {
            JsonNode row = value.get(rowIndex);
            require(row.isString() && row.asText().length() == BOARD_SIZE, "地图每行必须包含 26 个格子");
            String rowText = row.asText();
            for (int column = 0; column < rowText.length(); column++) {
                char material = rowText.charAt(column);
                require(material >= '0' && material <= '5', "地图材质只能是 0 到 5");
                if (rowIndex == 0 || column == 0 || rowIndex == BOARD_SIZE - 1 || column == BOARD_SIZE - 1) {
                    require(material == '2', "地图边界必须是钢墙");
                }
            }
            result.add(rowText);
        }
        return List.copyOf(result);
    }

    private static void requireEmpty(List<String> rows, GameMapCell cell) {
        require(rows.get(cell.getRow()).charAt(cell.getColumn()) == '0', "基地和出生点必须是空地");
    }

    private static void requireDistinctSpawns(GameMapCell base, List<GameMapCell> players,
                                              List<GameMapCell> enemies) {
        List<Integer> occupied = new ArrayList<Integer>();
        occupied.add(base.getRow() * BOARD_SIZE + base.getColumn());
        List<GameMapCell> spawns = new ArrayList<GameMapCell>(players);
        spawns.addAll(enemies);
        for (GameMapCell spawn : spawns) {
            int order = spawn.getRow() * BOARD_SIZE + spawn.getColumn();
            require(!occupied.contains(order), "基地和出生点不能重复");
            occupied.add(order);
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException("关卡配置无效：" + message);
        }
    }
}
