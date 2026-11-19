package com.syf.blog.service.game;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.dto.game.play.GameInitResponse;
import com.syf.blog.dto.game.play.GameStateResponse;
import com.syf.blog.dto.game.play.GameEndResponse;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.dto.game.ws.GameWsResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.runtime.GameBroadcast;
import com.syf.blog.game.engine.GameArenaMapFactory;
import com.syf.blog.game.engine.GameCoopEngine;
import com.syf.blog.service.GameMapService;
import com.syf.blog.websocket.game.GameRoomNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 编排地图读取、固定 tick 和锁外发送。
 * Lombok 构造参数依次为 GameRoomService、GameMapService、GameRoomNotifier。
 */
@Service
@RequiredArgsConstructor
public class GameMatchService {
    private final GameRoomService gameRoomService;
    private final GameMapService gameMapService;
    private final GameRoomNotifier gameRoomNotifier;
    private final GameArenaMapFactory arenaMapFactory = new GameArenaMapFactory();

    public RoomResponse start(Long userId, String roomId) {
        RoomResponse loading = gameRoomService.beginStart(userId, roomId, System.currentTimeMillis());
        gameRoomNotifier.notifyRoomUpdated(loading);

        GameInitResponse init;
        try {
            // 地图服务在自己的只读事务内查询。此处没有持有房间锁，也不在物理 tick 中。
            GameMapResponse map;
            if ("COOP".equals(loading.getMode())) {
                GameCoopEngine coopEngine = new GameCoopEngine();
                map = coopEngine.createMap();
            } else {
                map = gameMapService.getById(1L);
                map = arenaMapFactory.forOnlineMatch(map);
            }
            init = gameRoomService.installMap(roomId, loading.getMatchId(), map);
        } catch (RuntimeException exception) {
            RoomResponse canceled = gameRoomService.cancelLoading(roomId, loading.getMatchId());
            if (canceled != null) {
                gameRoomNotifier.notifyRoomUpdated(canceled);
            }
            if (exception instanceof BusinessException) {
                throw (BusinessException) exception;
            }
            System.out.println("[GameMatchService.start] 地图读取失败，已取消本次开局，roomId = " + roomId);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "地图加载失败，请稍后重试");
        }

        GameWsResponse<GameInitResponse> message = new GameWsResponse<GameInitResponse>("game:init", init);
        gameRoomNotifier.notifyGameMessage(loading.getMemberUserIds(), message);
        return loading;
    }

    public void loaded(Long userId, String roomId, String matchId) {
        GameStateResponse started = gameRoomService.confirmLoaded(userId, roomId, matchId);
        if (started == null) {
            return;
        }
        if ("FINISHED".equals(started.getStatus())) {
            GameEndResponse end = new GameEndResponse(roomId, matchId, started.getEndReason(), null,
                    started, started.getWinnerUserId());
            gameRoomNotifier.notifyUser(userId, new GameWsResponse<GameEndResponse>("game:end", end));
            return;
        }
        RoomResponse room = gameRoomService.getRoom(userId, roomId);
        gameRoomNotifier.notifyRoomUpdated(room);
        GameWsResponse<GameStateResponse> message = new GameWsResponse<GameStateResponse>("game:start", started);
        gameRoomNotifier.notifyGameMessage(room.getMemberUserIds(), message);
    }

    public void input(Long userId, String roomId, String matchId, long seq, Integer direction) {
        gameRoomService.submitInput(userId, roomId, matchId, seq, direction, System.currentTimeMillis());
    }

    public void input(Long userId, String roomId, String matchId, long seq, Integer direction, boolean fireHeld) {
        gameRoomService.submitInput(userId, roomId, matchId, seq, direction, System.currentTimeMillis(), fireHeld);
    }

    public RoomResponse rematch(Long userId, String roomId) {
        RoomResponse room = gameRoomService.rematch(userId, roomId);
        gameRoomNotifier.notifyRoomUpdated(room);
        return room;
    }

    public void sync(Long userId, String roomId) {
        RoomResponse room = gameRoomService.getRoom(userId, roomId);
        gameRoomNotifier.notifyUser(userId, new GameWsResponse<RoomResponse>("room:updated", room));
        GameInitResponse init = gameRoomService.getGameInit(userId, roomId);
        if (init != null) {
            gameRoomNotifier.notifyUser(userId, new GameWsResponse<GameInitResponse>("game:init", init));
        }
    }

    @Scheduled(fixedDelay = 50L)
    public void tick() {
        List<GameBroadcast> broadcasts = gameRoomService.advanceGames(System.currentTimeMillis());
        for (GameBroadcast broadcast : broadcasts) {
            gameRoomNotifier.notifyGameMessage(broadcast.getRecipientUserIds(), broadcast.getMessage());
        }
    }
}
