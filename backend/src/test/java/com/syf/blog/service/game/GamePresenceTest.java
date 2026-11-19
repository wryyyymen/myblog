package com.syf.blog.service.game;

import com.syf.blog.auth.UserPrincipal;
import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.dto.game.play.GameEndResponse;
import com.syf.blog.dto.game.play.GameStateResponse;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.runtime.GameBroadcast;
import com.syf.blog.websocket.game.GamePresenceListener;
import com.syf.blog.websocket.game.GameRoomNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 离线期限使用显式时间推进，不等待 15 秒、不访问数据库。 */
class GamePresenceTest {
    private final GameRoomService rooms = new GameRoomService();

    @Test
    void newMembersAreOnlineAndResponseListIsIndependent() {
        String roomId = rooms.createRoom(14L).getRoomId();
        RoomResponse first = rooms.joinRoom(15L, roomId);
        assertEquals(List.of(14L, 15L), first.getConnectedUserIds());
        first.getConnectedUserIds().clear();
        assertEquals(List.of(14L, 15L), rooms.getRoom(14L, roomId).getConnectedUserIds());
    }

    @Test
    void waitingDisconnectClearsOnlyOwnReadinessAndExpiresOnceWithoutExtendingDeadline() {
        String roomId = preparedRoom();
        long version = rooms.getRoom(14L, roomId).getVersion();
        rooms.markDisconnected(15L, 1_000L);
        RoomResponse offline = rooms.getRoom(14L, roomId);
        assertEquals(List.of(14L), offline.getConnectedUserIds());
        assertEquals(List.of(14L), offline.getReadyUserIds());
        assertEquals(version + 1L, offline.getVersion());
        assertEquals(2, offline.getMemberUserIds().size());
        assertThrows(BusinessException.class, () -> rooms.beginStart(14L, roomId, 2_000L));
        assertThrows(BusinessException.class, () -> rooms.setReady(15L, roomId, true));

        assertTrue(rooms.markDisconnected(15L, 10_000L).isEmpty());
        rooms.advanceGames(15_999L);
        assertEquals(2, rooms.getRoom(14L, roomId).getMemberUserIds().size());
        List<GameBroadcast> expired = rooms.advanceGames(16_000L);
        assertEquals(0, count(expired, "game:end"));
        assertEquals(1, count(expired, "room:left"));
        assertEquals(List.of(14L), rooms.getRoom(14L, roomId).getMemberUserIds());
        assertTrue(rooms.getRoom(14L, roomId).getReadyUserIds().isEmpty());
        assertTrue(rooms.advanceGames(16_050L).isEmpty());
        assertNotEquals(roomId, rooms.createRoom(15L).getRoomId());
    }

    @Test
    void reconnectWithinWindowRestoresSeatAndCancelsItsDeadline() {
        String roomId = preparedRoom();
        rooms.markDisconnected(15L, 1_000L);
        List<GameBroadcast> recovered = rooms.markConnected(15L, 15_999L);
        assertEquals(1, count(recovered, "room:updated"));
        assertEquals(List.of(14L, 15L), rooms.getRoom(14L, roomId).getConnectedUserIds());
        assertFalse(rooms.getRoom(14L, roomId).getReadyUserIds().contains(15L));
        rooms.advanceGames(30_000L);
        assertEquals(roomId, rooms.createRoom(15L).getRoomId());
    }

    @Test
    void reconnectAtExpiredDeadlineCannotRestoreOldSeatEvenBeforeTheNextTick() {
        String roomId = preparedRoom();
        rooms.markDisconnected(15L, 1_000L);
        List<GameBroadcast> broadcasts = rooms.markConnected(15L, 16_000L);
        assertEquals(1, count(broadcasts, "room:left"));
        assertEquals(List.of(14L), rooms.getRoom(14L, roomId).getMemberUserIds());
        assertNotEquals(roomId, rooms.createRoom(15L).getRoomId());
    }

    @Test
    void disconnectWhileLoadingCancelsMapAndRejectsLateResults() {
        String roomId = preparedRoom();
        RoomResponse loading = rooms.beginStart(14L, roomId, 1_000L);
        rooms.installMap(roomId, loading.getMatchId(), openMap());
        rooms.markDisconnected(15L, 2_000L);
        RoomResponse waiting = rooms.getRoom(14L, roomId);
        assertEquals("WAITING", waiting.getStatus());
        assertNull(waiting.getMatchId());
        assertTrue(waiting.getReadyUserIds().isEmpty());
        assertNull(rooms.getGameInit(14L, roomId));
        assertThrows(BusinessException.class, () -> rooms.installMap(roomId, loading.getMatchId(), openMap()));
        assertThrows(BusinessException.class, () -> rooms.confirmLoaded(15L, roomId, loading.getMatchId()));
    }

    @Test
    void playingDisconnectImmediatelyStopsInputAndReconnectPreservesSequenceAndMatch() {
        String roomId = playingRoom();
        String matchId = rooms.getRoom(14L, roomId).getMatchId();
        rooms.submitInput(14L, roomId, matchId, 7L, 3, 1_000L, true);
        double x = rooms.getGameInit(14L, roomId).getState().getTanks().get(0).getX();
        rooms.markDisconnected(14L, 1_001L);
        rooms.advanceGames(1_050L);
        rooms.advanceGames(1_100L);
        GameStateResponse disconnected = rooms.getGameInit(15L, roomId).getState();
        assertEquals(x, disconnected.getTanks().get(0).getX(), 0.0001);
        assertTrue(disconnected.getBullets().isEmpty());
        assertThrows(BusinessException.class, () -> rooms.submitInput(14L, roomId, matchId, 8L, 3, 1_100L, true));

        rooms.markConnected(14L, 2_000L);
        rooms.submitInput(14L, roomId, matchId, 8L, 3, 2_001L, false);
        rooms.advanceGames(2_050L);
        GameStateResponse restored = rooms.getGameInit(14L, roomId).getState();
        assertEquals(matchId, restored.getMatchId());
        assertEquals(8L, restored.getTanks().get(0).getLastInputSeq());
        assertTrue(restored.getTanks().get(0).getX() > x);
    }

    @Test
    void playingTimeoutAwardsOnlineOpponentThenReleasesOwnerAndTransfersRoom() {
        String roomId = playingRoom();
        rooms.markDisconnected(14L, 1_000L);
        List<GameBroadcast> broadcasts = rooms.advanceGames(16_000L);
        GameEndResponse end = gameEnd(broadcasts);
        assertEquals("PLAYER_DISCONNECTED", end.getReason());
        assertEquals(15L, end.getWinnerUserId());
        assertEquals("FINISHED", end.getState().getStatus());
        RoomResponse remaining = rooms.getRoom(15L, roomId);
        assertEquals("WAITING", remaining.getStatus());
        assertEquals(15L, remaining.getOwnerUserId());
        assertEquals(List.of(15L), remaining.getMemberUserIds());
        assertEquals(List.of(15L), remaining.getConnectedUserIds());
        assertNull(remaining.getMatchId());
        assertTrue(rooms.advanceGames(17_000L).isEmpty());
    }

    @Test
    void bothDisconnectedEndWithoutWinnerAndEmptyRoomIsReclaimed() {
        String roomId = playingRoom();
        rooms.markDisconnected(14L, 1_000L);
        rooms.markDisconnected(15L, 1_000L);
        List<GameBroadcast> broadcasts = rooms.advanceGames(16_000L);
        GameEndResponse end = gameEnd(broadcasts);
        assertEquals("ABANDONED", end.getReason());
        assertNull(end.getWinnerUserId());
        assertThrows(BusinessException.class, () -> rooms.joinRoom(16L, roomId));
        assertNotEquals(roomId, rooms.createRoom(14L).getRoomId());
        assertNotEquals(roomId, rooms.createRoom(15L).getRoomId());
        assertTrue(rooms.advanceGames(17_000L).isEmpty());
    }

    @Test
    void additionalConnectionAndOldCloseCallbackCannotDisconnectNewConnection() {
        GameRoomNotifier notifier = mock(GameRoomNotifier.class);
        GamePresenceListener listener = new GamePresenceListener(rooms, notifier);
        listener.connected(connected(14L, "first"));
        String roomId = rooms.createRoom(14L).getRoomId();
        listener.connected(connected(14L, "second"));
        listener.disconnected(disconnected(14L, "first"));
        listener.disconnected(disconnected(14L, "first"));
        assertEquals(List.of(14L), rooms.getRoom(14L, roomId).getConnectedUserIds());

        listener.disconnected(disconnected(14L, "second"));
        assertTrue(rooms.getRoom(14L, roomId).getConnectedUserIds().isEmpty());
        listener.connected(connected(14L, "third"));
        listener.disconnected(disconnected(14L, "second"));
        assertEquals(List.of(14L), rooms.getRoom(14L, roomId).getConnectedUserIds());
    }

    @Test
    void closedCallbackBeforeConnectedCallbackCannotLeaveAGhostConnection() {
        GameRoomNotifier notifier = mock(GameRoomNotifier.class);
        GamePresenceListener listener = new GamePresenceListener(rooms, notifier);
        String roomId = rooms.createRoom(14L).getRoomId();

        listener.disconnected(disconnected(14L, "already-closed"));
        listener.connected(connected(14L, "already-closed"));
        listener.connected(connected(14L, "fresh-connection"));
        listener.disconnected(disconnected(14L, "fresh-connection"));

        // 如果迟到的 connected 把旧连接复活，这里会误以为还有一条连接在线。
        assertTrue(rooms.getRoom(14L, roomId).getConnectedUserIds().isEmpty());
    }

    @Test
    void finishedBattleIsNotSettledAgainWhenOfflineSeatExpires() {
        String roomId = playingRoom();
        // 以对局时间上限正常结束，再让一个成员离线。
        for (int tick = 0; tick < 3_600; tick++) {
            rooms.advanceGames(1_000L + tick * 50L);
        }
        assertEquals("FINISHED", rooms.getRoom(14L, roomId).getStatus());
        rooms.markDisconnected(14L, 200_000L);
        List<GameBroadcast> expired = rooms.advanceGames(215_000L);
        assertEquals(0, count(expired, "game:end"));
        assertEquals("WAITING", rooms.getRoom(15L, roomId).getStatus());
    }

    private String preparedRoom() {
        String roomId = rooms.createRoom(14L).getRoomId();
        rooms.joinRoom(15L, roomId);
        rooms.setReady(14L, roomId, true);
        rooms.setReady(15L, roomId, true);
        return roomId;
    }

    private String playingRoom() {
        String roomId = preparedRoom();
        String matchId = rooms.beginStart(14L, roomId, 1_000L).getMatchId();
        rooms.installMap(roomId, matchId, openMap());
        rooms.confirmLoaded(14L, roomId, matchId);
        rooms.confirmLoaded(15L, roomId, matchId);
        return roomId;
    }

    private GameMapResponse openMap() {
        List<Integer> row = List.of(0, 0, 0, 0, 0, 0, 0, 0);
        return new GameMapResponse(1L, "连接测试地图", 24, List.of(row, row, row, row, row, row, row, row));
    }

    private int count(List<GameBroadcast> broadcasts, String type) {
        int matches = 0;
        for (GameBroadcast broadcast : broadcasts) {
            if (type.equals(broadcast.getMessage().getType())) {
                matches++;
            }
        }
        return matches;
    }

    private GameEndResponse gameEnd(List<GameBroadcast> broadcasts) {
        for (GameBroadcast broadcast : broadcasts) {
            if ("game:end".equals(broadcast.getMessage().getType())) {
                return (GameEndResponse) broadcast.getMessage().getPayload();
            }
        }
        throw new AssertionError("应该生成一次断线结算");
    }

    private SessionConnectedEvent connected(Long userId, String sessionId) {
        UserPrincipal principal = new UserPrincipal(userId);
        Message<byte[]> message = eventMessage(principal, sessionId, StompCommand.CONNECTED);
        return new SessionConnectedEvent(this, message, principal);
    }

    private SessionDisconnectEvent disconnected(Long userId, String sessionId) {
        UserPrincipal principal = new UserPrincipal(userId);
        Message<byte[]> message = eventMessage(principal, sessionId, StompCommand.DISCONNECT);
        return new SessionDisconnectEvent(this, message, sessionId, CloseStatus.NORMAL, principal);
    }

    private Message<byte[]> eventMessage(UserPrincipal principal, String sessionId, StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        accessor.setUser(principal);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
