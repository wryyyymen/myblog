package com.syf.blog.game.runtime;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.game.map.GameLevelDefinition;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单局运行状态，由房间服务持锁访问，移动、战斗与合作 AI 引擎负责推进。
 * Lombok 构造参数依次为 roomId、matchId、map、tanks。
 */
@Getter
@RequiredArgsConstructor
public class GameMatch {

    private final String roomId;

    private final String matchId;

    // 已验证并深复制的本局地图，不与数据库实体共用 seed 列表。
    private final GameMapResponse map;

    // 顺序与房间成员顺序一致，坦克对象本身由服务端修改。
    private final List<TankState> tanks;

    private final Map<Long, PlayerInput> inputs = new HashMap<Long, PlayerInput>();

    private final Set<Long> loadedUserIds = new LinkedHashSet<Long>();

    // 本局子弹和已破坏砖墙独立保存，不修改数据库地图模板。
    private final List<BulletState> bullets = new ArrayList<BulletState>();

    // 使用 row * columns + column 标记被打破的砖墙。
    private final Set<Integer> destroyedCells = new LinkedHashSet<Integer>();

    @Setter
    private String mode = "PVP";

    // 开局后固定使用本关模板，地图、敌人总数和速度来自同一份配置。
    @Setter
    private GameLevelDefinition level;

    // AI 使用负数身份，永远不能通过客户端输入接口操作。
    private final List<TankState> enemies = new ArrayList<TankState>();
    private final Map<Long, EnemyBrain> enemyBrains = new HashMap<Long, EnemyBrain>();

    @Setter
    private BaseState base;

    @Setter
    private int enemySpawned;

    @Setter
    private long nextSpawnTick;

    @Setter
    private String teamResult;

    /** 返回本次计算使用的列表副本，避免改变两名人类玩家的固定名单。 */
    public List<TankState> allTanks() {
        List<TankState> all = new ArrayList<TankState>(tanks);
        all.addAll(enemies);
        return all;
    }

    @Setter
    private long nextBulletId = 1L;

    @Setter
    private boolean finished;

    @Setter
    private Long winnerUserId;

    @Setter
    private String endReason;

    @Setter
    private long tick;
}
