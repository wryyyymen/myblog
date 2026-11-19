package com.syf.blog.service.game;

import com.syf.blog.dto.game.GameMapResponse;
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
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 真实房间服务、对局服务和通知器协作，仅替换地图查询和发送组件。
 * 不启动 Spring、不连接数据库，规则时间由测试显式提供。
 */
class GameMatchServiceTest {

    private final GameRoomService rooms = new GameRoomService();
    private final GameMapService maps = mock(GameMapService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final GameRoomNotifier notifier = new GameRoomNotifier(messaging);
    private final GameMatchService matches = new GameMatchService(rooms, maps, notifier);

    @Test
    void startRequiresOwnerAndTwoPreparedMembersBeforeLoadingTheMap() {
        String roomId = rooms.createRoom(14L).getRoomId();
        assertThrows(BusinessException.class, () -> matches.start(14L, roomId));
        rooms.joinRoom(15L, roomId);
        assertThrows(BusinessException.class, () -> matches.start(14L, roomId));
        rooms.setReady(14L, roomId, true);
        assertThrows(BusinessException.class, () -> matches.start(14L, roomId));
        rooms.setReady(15L, roomId, true);
        assertThrows(BusinessException.class, () -> matches.start(15L, roomId));
        assertThrows(BusinessException.class, () -> matches.start(99L, roomId));

        assertEquals("WAITING", rooms.getRoom(14L, roomId).getStatus());
        assertNull(rooms.getRoom(14L, roomId).getMatchId());
        verifyNoInteractions(maps, messaging);
    }

    @Test
    void startLoadsOneMapAndSendsOneMatchToBothMembersOnly() {
        String roomId = preparedRoom(14L, 15L);
        rooms.createRoom(99L);
        when(maps.getById(1L)).thenReturn(openMap());

        RoomResponse started = matches.start(14L, roomId);

        assertEquals("LOADING", started.getStatus());
        assertNotNull(started.getMatchId());
        GameInitResponse ownerInit = (GameInitResponse) notification("14", "game:init").getPayload();
        GameInitResponse otherInit = (GameInitResponse) notification("15", "game:init").getPayload();
        assertEquals(started.getMatchId(), ownerInit.getMatchId());
        assertEquals(ownerInit.getMatchId(), otherInit.getMatchId());
        assertEquals(2, ownerInit.getState().getTanks().size());
        assertEquals("LOADING", ownerInit.getState().getStatus());
        assertFalse(hasNotification("99", "game:init"));

        // 重复开始不再查询地图，也不能为当前房间换一个对局号。
        assertThrows(BusinessException.class, () -> matches.start(14L, roomId));
        assertEquals(started.getMatchId(), rooms.getRoom(14L, roomId).getMatchId());
        verify(maps).getById(1L);
    }

    @Test
    void mapQueryFailureRestoresWaitingAndClearsReadiness() {
        String roomId = preparedRoom(14L, 15L);
        when(maps.getById(1L)).thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "地图不存在"));

        assertThrows(BusinessException.class, () -> matches.start(14L, roomId));

        RoomResponse room = rooms.getRoom(14L, roomId);
        assertEquals("WAITING", room.getStatus());
        assertNull(room.getMatchId());
        assertTrue(room.getReadyUserIds().isEmpty());
        assertNull(rooms.getGameInit(14L, roomId));
        assertFalse(hasNotification("14", "game:init"));
    }

    @Test
    void unavailableSpawnMapAlsoRollsBackLoadingWithoutInstallingAMatch() {
        String roomId = preparedRoom(14L, 15L);
        GameMapResponse blockedMap = new GameMapResponse(1L, "无出生点地图", 24,
                List.of(List.of(1, 1), List.of(1, 1)));
        when(maps.getById(1L)).thenReturn(blockedMap);

        assertThrows(BusinessException.class, () -> matches.start(14L, roomId));

        RoomResponse room = rooms.getRoom(14L, roomId);
        assertEquals("WAITING", room.getStatus());
        assertTrue(room.getReadyUserIds().isEmpty());
        assertNull(rooms.getGameInit(14L, roomId));
    }

    @Test
    void onlyTheTwoCurrentMembersLoadedAcknowledgementsStartTheMatch() {
        String roomId = preparedRoom(14L, 15L);
        when(maps.getById(1L)).thenReturn(openMap());
        String matchId = matches.start(14L, roomId).getMatchId();
        clearInvocations(messaging);

        assertThrows(BusinessException.class, () -> matches.loaded(99L, roomId, matchId));
        assertThrows(BusinessException.class, () -> matches.loaded(14L, roomId, "old-match"));
        matches.loaded(14L, roomId, matchId);
        matches.loaded(14L, roomId, matchId);
        assertEquals("LOADING", rooms.getRoom(14L, roomId).getStatus());
        assertFalse(hasNotification("14", "game:start"));

        matches.loaded(15L, roomId, matchId);
        assertEquals("PLAYING", rooms.getRoom(14L, roomId).getStatus());
        GameStateResponse first = (GameStateResponse) notification("14", "game:start").getPayload();
        GameStateResponse second = (GameStateResponse) notification("15", "game:start").getPayload();
        assertEquals(matchId, first.getMatchId());
        assertEquals(first.getTick(), second.getTick());
        assertEquals("PLAYING", first.getStatus());
    }

    @Test
    void loadedMatchCannotAcceptNewPlayersOrReadyChanges() {
        String roomId = playingRoom(14L, 15L);
        String matchId = rooms.getRoom(14L, roomId).getMatchId();
        assertThrows(BusinessException.class, () -> rooms.joinRoom(16L, roomId));
        assertThrows(BusinessException.class, () -> rooms.setReady(14L, roomId, false));
        assertThrows(BusinessException.class, () -> matches.start(14L, roomId));

        assertEquals("PLAYING", rooms.getRoom(14L, roomId).getStatus());
        assertEquals(matchId, rooms.getRoom(14L, roomId).getMatchId());
        assertEquals(List.of(14L, 15L), rooms.getRoom(14L, roomId).getMemberUserIds());
    }

    @Test
    void inputRequiresPlayingMemberCurrentMatchAndValidDirection() {
        String roomId = preparedRoom(14L, 15L);
        when(maps.getById(1L)).thenReturn(openMap());
        String matchId = matches.start(14L, roomId).getMatchId();
        assertThrows(BusinessException.class, () -> matches.input(14L, roomId, matchId, 1L, 3));
        matches.loaded(14L, roomId, matchId);
        matches.loaded(15L, roomId, matchId);

        assertThrows(BusinessException.class, () -> matches.input(99L, roomId, matchId, 1L, 3));
        assertThrows(BusinessException.class, () -> matches.input(14L, roomId, "old-match", 1L, 3));
        assertThrows(BusinessException.class, () -> matches.input(14L, roomId, matchId, 0L, 3));
        assertThrows(BusinessException.class, () -> matches.input(14L, roomId, matchId, 1L, 4));
        assertEquals(0L, ownTank(roomId, 14L).getLastInputSeq());
    }

    @Test
    void oldInputDoesNotOverwriteNewerDirectionOrItsSequence() {
        String roomId = playingRoom(14L, 15L);
        String matchId = rooms.getRoom(14L, roomId).getMatchId();
        double originalX = ownTank(roomId, 14L).getX();
        rooms.submitInput(14L, roomId, matchId, 5L, 3, 1_000L);
        rooms.submitInput(14L, roomId, matchId, 4L, 0, 1_100L);
        rooms.advanceGames(1_100L);

        TankResponse tank = ownTank(roomId, 14L);
        assertEquals(5L, tank.getLastInputSeq());
        assertEquals(3, tank.getDirection());
        assertTrue(tank.getX() > originalX);
    }

    @Test
    void crossingRoomInputCannotChangeEitherMatch() {
        String firstRoom = playingRoom(14L, 15L);
        String secondRoom = playingRoom(16L, 17L);
        String firstMatch = rooms.getRoom(14L, firstRoom).getMatchId();
        String secondMatch = rooms.getRoom(16L, secondRoom).getMatchId();
        assertNotEquals(firstMatch, secondMatch);

        assertThrows(BusinessException.class,
                () -> matches.input(14L, secondRoom, secondMatch, 1L, 3));
        assertThrows(BusinessException.class,
                () -> matches.input(14L, firstRoom, secondMatch, 1L, 3));
        assertEquals(0L, ownTank(firstRoom, 14L).getLastInputSeq());
        assertEquals(0L, ownTank(secondRoom, 16L).getLastInputSeq());
    }

    @Test
    void departureDuringMapQueryCannotResurrectTheCancelledMatch() {
        String roomId = preparedRoom(14L, 15L);
        when(maps.getById(1L)).thenAnswer(new DepartingPlayerMapAnswer(rooms, roomId));

        assertThrows(BusinessException.class, () -> matches.start(14L, roomId));

        RoomResponse remaining = rooms.getRoom(14L, roomId);
        assertEquals("WAITING", remaining.getStatus());
        assertEquals(List.of(14L), remaining.getMemberUserIds());
        assertTrue(remaining.getReadyUserIds().isEmpty());
        assertNull(remaining.getMatchId());
        assertNull(rooms.getGameInit(14L, roomId));
        assertFalse(hasNotification("14", "game:init"));
    }

    @Test
    void loadingTimeoutClearsMatchAndRejectsLateLoadedAcknowledgements() {
        String roomId = preparedRoom(14L, 15L);
        String matchId = rooms.beginStart(14L, roomId, 1_000L).getMatchId();
        rooms.installMap(roomId, matchId, openMap());
        assertTrue(rooms.advanceGames(1_050L).isEmpty());
        assertEquals(0L, rooms.getGameInit(14L, roomId).getState().getTick());

        List<GameBroadcast> timeoutMessages = rooms.advanceGames(31_001L);
        assertEquals(1, timeoutMessages.size());
        assertEquals("room:updated", timeoutMessages.get(0).getMessage().getType());
        RoomResponse room = rooms.getRoom(14L, roomId);
        assertEquals("WAITING", room.getStatus());
        assertTrue(room.getReadyUserIds().isEmpty());
        assertNull(room.getMatchId());
        assertThrows(BusinessException.class, () -> matches.loaded(14L, roomId, matchId));
    }

    @Test
    void cancellationForOldMatchCannotCancelANewLoadingMatch() {
        String roomId = preparedRoom(14L, 15L);
        String oldMatch = rooms.beginStart(14L, roomId, 1_000L).getMatchId();
        rooms.cancelLoading(roomId, oldMatch);
        rooms.setReady(14L, roomId, true);
        rooms.setReady(15L, roomId, true);
        String newMatch = rooms.beginStart(14L, roomId, 2_000L).getMatchId();

        assertNull(rooms.cancelLoading(roomId, oldMatch));
        assertEquals("LOADING", rooms.getRoom(14L, roomId).getStatus());
        assertEquals(newMatch, rooms.getRoom(14L, roomId).getMatchId());
        assertNotEquals(oldMatch, newMatch);
    }

    @Test
    void syncTargetsOnlyTheRequestingUserAndKeepsCurrentMatch() {
        String roomId = playingRoom(14L, 15L);
        String matchId = rooms.getRoom(14L, roomId).getMatchId();
        clearInvocations(messaging);

        matches.sync(14L, roomId);

        GameInitResponse init = (GameInitResponse) notification("14", "game:init").getPayload();
        assertEquals(matchId, init.getMatchId());
        assertEquals("PLAYING", init.getState().getStatus());
        assertFalse(hasNotification("15", "game:init"));
        assertThrows(BusinessException.class, () -> matches.sync(99L, roomId));
    }

    @Test
    void departureDuringPlayingReturnsFinishedSnapshotAndStopsOldInputs() {
        String roomId = playingRoom(14L, 15L);
        String matchId = rooms.getRoom(14L, roomId).getMatchId();

        RoomLeaveResult result = rooms.leaveRoom(14L, roomId);

        assertNotNull(result.getEndedGame());
        assertEquals("PLAYER_LEFT", result.getEndedGame().getReason());
        assertEquals(14L, result.getEndedGame().getLeavingUserId());
        assertEquals(matchId, result.getEndedGame().getMatchId());
        assertEquals("FINISHED", result.getEndedGame().getState().getStatus());
        assertEquals(2, result.getEndedGame().getState().getTanks().size());
        assertEquals("WAITING", result.getRemainingRoomResponse().getStatus());
        assertEquals(15L, result.getRemainingRoomResponse().getOwnerUserId());
        assertTrue(result.getRemainingRoomResponse().getReadyUserIds().isEmpty());
        assertNull(rooms.getGameInit(15L, roomId));
        assertThrows(BusinessException.class, () -> matches.input(15L, roomId, matchId, 1L, 3));
    }

    private String preparedRoom(Long ownerId, Long otherId) {
        String roomId = rooms.createRoom(ownerId).getRoomId();
        rooms.joinRoom(otherId, roomId);
        rooms.setReady(ownerId, roomId, true);
        rooms.setReady(otherId, roomId, true);
        return roomId;
    }

    private String playingRoom(Long ownerId, Long otherId) {
        String roomId = preparedRoom(ownerId, otherId);
        when(maps.getById(1L)).thenReturn(openMap());
        String matchId = matches.start(ownerId, roomId).getMatchId();
        matches.loaded(ownerId, roomId, matchId);
        matches.loaded(otherId, roomId, matchId);
        return roomId;
    }

    private TankResponse ownTank(String roomId, Long userId) {
        GameInitResponse init = rooms.getGameInit(userId, roomId);
        for (TankResponse tank : init.getState().getTanks()) {
            if (tank.getUserId().equals(userId)) return tank;
        }
        fail("快照中没有指定玩家的坦克");
        return null;
    }

    private GameWsResponse<?> notification(String userName, String type) {
        for (Invocation invocation : mockingDetails(messaging).getInvocations()) {
            Object[] arguments = invocation.getArguments();
            if ("convertAndSendToUser".equals(invocation.getMethod().getName())
                    && arguments.length >= 3 && userName.equals(arguments[0])
                    && arguments[2] instanceof GameWsResponse<?>) {
                GameWsResponse<?> response = (GameWsResponse<?>) arguments[2];
                if (type.equals(response.getType())) return response;
            }
        }
        fail("缺少目标用户的通知，user=" + userName + "，type=" + type);
        return null;
    }

    private boolean hasNotification(String userName, String type) {
        for (Invocation invocation : mockingDetails(messaging).getInvocations()) {
            Object[] arguments = invocation.getArguments();
            if ("convertAndSendToUser".equals(invocation.getMethod().getName())
                    && arguments.length >= 3 && userName.equals(arguments[0])
                    && arguments[2] instanceof GameWsResponse<?>) {
                GameWsResponse<?> response = (GameWsResponse<?>) arguments[2];
                if (type.equals(response.getType())) return true;
            }
        }
        return false;
    }

    private static GameMapResponse openMap() {
        List<List<Integer>> seed = new ArrayList<List<Integer>>();
        for (int row = 0; row < 8; row++) {
            List<Integer> cells = new ArrayList<Integer>();
            for (int column = 0; column < 8; column++) {
                boolean border = row == 0 || row == 7 || column == 0 || column == 7;
                if (border) cells.add(1);
                else cells.add(0);
            }
            seed.add(cells);
        }
        GameMapResponse map = new GameMapResponse(1L, "隔离测试地图", 24, seed);
        return map;
    }

    @RequiredArgsConstructor
    private static final class DepartingPlayerMapAnswer implements Answer<GameMapResponse> {
        private final GameRoomService rooms;
        private final String roomId;

        @Override
        public GameMapResponse answer(InvocationOnMock invocation) {
            rooms.leaveRoom(15L, roomId);
            GameMapResponse map = openMap();
            return map;
        }
    }
}
