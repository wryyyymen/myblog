package com.syf.blog.game.runtime;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 后端内存中的游戏房间。
 * 房间数据由 GameRoomService 统一管理。
 */
@Getter
@RequiredArgsConstructor
public class GameRoom {

    // 房间的唯一编号
    private final String roomId;

    // 房主退出时需要转移，因此只为该字段提供 setter。
    // @NonNull 让 RequiredArgsConstructor 仍生成 (roomId, ownerUserId) 构造方法。
    @NonNull
    @Setter
    private Long ownerUserId;

    // 房间中所有成员的用户 ID，包含房主
    // Set 不保存重复元素；LinkedHashSet 保留加入顺序
    private final Set<Long> memberUserIds = new LinkedHashSet<Long>();

    // 只保存已准备成员的 ID；成员变化时全部清空，要求重新确认准备
    private final Set<Long> readyUserIds = new LinkedHashSet<Long>();

    // 在线成员及离线席位的保留期限，只由 GameRoomService 在同步方法中修改。
    private final Set<Long> connectedUserIds = new LinkedHashSet<Long>();

    private final Map<Long, Long> disconnectedUntilMillis = new HashMap<Long, Long>();

    // 成员或准备状态真实变化时递增；只在房间服务的同步方法中修改
    @Setter
    private long version = 1L;

    @Setter
    private String status = "WAITING";

    @Setter
    private String mode = "PVP";

    @Setter
    private String matchId;

    @Setter
    private long loadingDeadlineMillis;

    @Setter
    private GameMatch match;
}
