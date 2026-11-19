package com.syf.blog.service.game;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.dto.game.play.GameEndResponse;
import com.syf.blog.dto.game.play.GameInitResponse;
import com.syf.blog.dto.game.play.GameStateResponse;
import com.syf.blog.dto.game.play.TankResponse;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.dto.game.ws.GameWsResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.runtime.GameBroadcast;
import com.syf.blog.game.runtime.RoomLeaveResult;
import com.syf.blog.service.GameMapService;
import com.syf.blog.websocket.game.GameRoomNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通过真实输入、固定步长和战斗规则走完一局，不直接改写坦克血量或胜负。
 * 仅地图查询与消息发送使用模拟对象，不连接数据库。
 */
class GameCombatFlowTest {

    private final GameRoomService rooms = new GameRoomService();
    private final GameMapService maps = mock(GameMapService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final GameRoomNotifier notifier = new GameRoomNotifier(messaging);
    private final GameMatchService matches = new GameMatchService(rooms, maps, notifier);

    @Test
    void threeHitsFinishTheMatchOnceAndRejectFurtherInputs() {
        String roomId = playingRoom(false);
        String matchId = rooms.getRoom(14L, roomId).getMatchId();

        List<GameBroadcast> broadcasts = finishByShooting(roomId, matchId, false);

        GameEndResponse ended = onlyGameEnd(broadcasts);
        assertEquals("DESTROYED", ended.getReason());
        assertEquals(14L, ended.getWinnerUserId());
        assertEquals("FINISHED", ended.getState().getStatus());
        assertEquals(3, tank(ended.getState(), 14L).getHp());
        assertEquals(0, tank(ended.getState(), 15L).getHp());
        assertEquals("FINISHED", rooms.getRoom(14L, roomId).getStatus());
        assertTrue(rooms.getRoom(14L, roomId).getReadyUserIds().isEmpty());

        GameStateResponse finalState = rooms.getGameInit(14L, roomId).getState();
        long finalTick = finalState.getTick();
        assertTrue(rooms.advanceGames(100_000L).isEmpty());
        assertTrue(rooms.advanceGames(100_050L).isEmpty());
        assertEquals(finalTick, rooms.getGameInit(14L, roomId).getState().getTick());
        assertThrows(BusinessException.class,
                () -> matches.input(14L, roomId, matchId, 999L, null, true));
    }

    @Test
    void onlyFinishedRoomOwnerCanRematchAndNewMatchResetsAllCombatState() {
        String roomId = playingRoom(true);
        String oldMatchId = rooms.getRoom(14L, roomId).getMatchId();
        assertThrows(BusinessException.class, () -> matches.rematch(14L, roomId));
        finishByShooting(roomId, oldMatchId, true);
        GameStateResponse oldFinal = rooms.getGameInit(14L, roomId).getState();
        assertTrue(oldFinal.getDestroyedCells().contains(17));
        assertEquals(0, tank(oldFinal, 15L).getHp());

        assertThrows(BusinessException.class, () -> matches.rematch(15L, roomId));
        assertThrows(BusinessException.class, () -> matches.rematch(99L, roomId));
        assertEquals("FINISHED", rooms.getRoom(14L, roomId).getStatus());

        clearInvocations(messaging);
        RoomResponse waiting = matches.rematch(14L, roomId);
        assertEquals("WAITING", waiting.getStatus());
        assertNull(waiting.getMatchId());
        assertTrue(waiting.getReadyUserIds().isEmpty());
        assertNull(rooms.getGameInit(14L, roomId));
        assertTrue(hasNotification("14", "room:updated"));
        assertTrue(hasNotification("15", "room:updated"));
        assertThrows(BusinessException.class, () -> matches.rematch(14L, roomId));

        rooms.setReady(14L, roomId, true);
        rooms.setReady(15L, roomId, true);
        String newMatchId = matches.start(14L, roomId).getMatchId();
        assertNotEquals(oldMatchId, newMatchId);
        GameInitResponse fresh = rooms.getGameInit(14L, roomId);
        assertEquals(0L, fresh.getState().getTick());
        assertTrue(fresh.getState().getBullets().isEmpty());
        assertTrue(fresh.getState().getDestroyedCells().isEmpty());
        assertNull(fresh.getState().getWinnerUserId());
        assertNull(fresh.getState().getEndReason());
        assertEquals(1, fresh.getMap().seed().get(2).get(1));
        for (TankResponse tank : fresh.getState().getTanks()) {
            assertEquals(3, tank.getHp());
            assertEquals(0L, tank.getLastInputSeq());
        }
        assertThrows(BusinessException.class, () -> matches.loaded(14L, roomId, oldMatchId));
        assertThrows(BusinessException.class,
                () -> matches.input(14L, roomId, oldMatchId, 999L, null, true));
    }

    @Test
    void finishedSnapshotCanBeSyncedWithoutRestartingTheSimulation() {
        String roomId = playingRoom(false);
        String matchId = rooms.getRoom(14L, roomId).getMatchId();
        finishByShooting(roomId, matchId, false);
        long finalTick = rooms.getGameInit(14L, roomId).getState().getTick();
        clearInvocations(messaging);

        matches.sync(15L, roomId);
        assertTrue(hasNotification("15", "game:init"));
        assertFalse(hasNotification("14", "game:init"));
        matches.loaded(15L, roomId, matchId);
        assertTrue(hasNotification("15", "game:end"));
        assertFalse(hasNotification("14", "game:end"));
        assertEquals(finalTick, rooms.getGameInit(15L, roomId).getState().getTick());
        assertEquals("FINISHED", rooms.getRoom(15L, roomId).getStatus());
    }

    @Test
    void leavingAnAlreadyFinishedMatchDoesNotSettleItASecondTime() {
        String roomId = playingRoom(false);
        String matchId = rooms.getRoom(14L, roomId).getMatchId();
        finishByShooting(roomId, matchId, false);

        RoomLeaveResult left = rooms.leaveRoom(14L, roomId);

        assertNull(left.getEndedGame());
        assertEquals("WAITING", left.getRemainingRoomResponse().getStatus());
        assertEquals(15L, left.getRemainingRoomResponse().getOwnerUserId());
        assertNull(rooms.getGameInit(15L, roomId));
    }

    @Test
    void activePlayerDepartureAwardsRemainingPlayerWithoutLeavingRunningMatch() {
        String roomId = playingRoom(false);

        RoomLeaveResult left = rooms.leaveRoom(14L, roomId);

        assertNotNull(left.getEndedGame());
        assertEquals("PLAYER_LEFT", left.getEndedGame().getReason());
        assertEquals(15L, left.getEndedGame().getWinnerUserId());
        assertEquals("WAITING", left.getRemainingRoomResponse().getStatus());
        assertTrue(rooms.advanceGames(10_000L).isEmpty());
    }

    @Test
    void rematchBeforeAnyMatchCannotChangeWaitingRoomOrSendNotifications() {
        String roomId = rooms.createRoom(14L).getRoomId();
        rooms.joinRoom(15L, roomId);
        long originalVersion = rooms.getRoom(14L, roomId).getVersion();

        assertThrows(BusinessException.class, () -> matches.rematch(14L, roomId));

        assertEquals("WAITING", rooms.getRoom(14L, roomId).getStatus());
        assertEquals(originalVersion, rooms.getRoom(14L, roomId).getVersion());
        verifyNoInteractions(messaging);
    }

    private String playingRoom(boolean brickAboveOwner) {
        String roomId = rooms.createRoom(14L).getRoomId();
        rooms.joinRoom(15L, roomId);
        rooms.setReady(14L, roomId, true);
        rooms.setReady(15L, roomId, true);
        when(maps.getById(1L)).thenReturn(corridorMap(brickAboveOwner));
        String matchId = matches.start(14L, roomId).getMatchId();
        matches.loaded(14L, roomId, matchId);
        matches.loaded(15L, roomId, matchId);
        return roomId;
    }

    private List<GameBroadcast> finishByShooting(String roomId, String matchId, boolean breakBrickFirst) {
        List<GameBroadcast> broadcasts = new ArrayList<GameBroadcast>();
        for (int step = 0; step < 100; step++) {
            long nowMillis = 1_000L + step * 50L;
            Integer direction = null;
            if (breakBrickFirst) {
                direction = step == 0 ? 0 : 3;
            }
            rooms.submitInput(14L, roomId, matchId, step + 1L, direction, nowMillis, true);
            broadcasts.addAll(rooms.advanceGames(nowMillis));
            if ("FINISHED".equals(rooms.getRoom(14L, roomId).getStatus())) {
                return broadcasts;
            }
        }
        fail("连续射击未在预期步数内击毁同一通道中的对手");
        return broadcasts;
    }

    private GameEndResponse onlyGameEnd(List<GameBroadcast> broadcasts) {
        GameEndResponse ended = null;
        int count = 0;
        for (GameBroadcast broadcast : broadcasts) {
            if ("game:end".equals(broadcast.getMessage().getType())) {
                count++;
                ended = (GameEndResponse) broadcast.getMessage().getPayload();
            }
        }
        assertEquals(1, count);
        assertNotNull(ended);
        return ended;
    }

    private TankResponse tank(GameStateResponse state, Long userId) {
        for (TankResponse tank : state.getTanks()) {
            if (tank.getUserId().equals(userId)) return tank;
        }
        fail("状态中缺少玩家坦克");
        return null;
    }

    private boolean hasNotification(String userName, String type) {
        for (Invocation invocation : mockingDetails(messaging).getInvocations()) {
            Object[] arguments = invocation.getArguments();
            if ("convertAndSendToUser".equals(invocation.getMethod().getName())
                    && arguments.length >= 3 && userName.equals(arguments[0])
                    && arguments[2] instanceof GameWsResponse<?>) {
                GameWsResponse<?> message = (GameWsResponse<?>) arguments[2];
                if (type.equals(message.getType())) return true;
            }
        }
        return false;
    }

    private GameMapResponse corridorMap(boolean brickAboveOwner) {
        List<List<Integer>> seed = new ArrayList<List<Integer>>();
        for (int row = 0; row < 8; row++) {
            List<Integer> cells = new ArrayList<Integer>();
            for (int column = 0; column < 8; column++) {
                if (row == 3 && column >= 1 && column <= 6) cells.add(0);
                else if (brickAboveOwner && row == 2 && column == 1) cells.add(1);
                else cells.add(2);
            }
            seed.add(cells);
        }
        return new GameMapResponse(1L, "隔离战斗通道", 24, seed);
    }
}
