package com.syf.blog.service.game;

import com.syf.blog.dto.game.play.GameInitResponse;
import com.syf.blog.dto.game.play.GameStateResponse;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.runtime.GameBroadcast;
import com.syf.blog.game.runtime.BulletState;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.GameRoom;
import com.syf.blog.game.runtime.RoomLeaveResult;
import com.syf.blog.service.GameMapService;
import com.syf.blog.websocket.game.GameRoomNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameCoopServiceTest {
    private final GameRoomService rooms = new GameRoomService();
    private final GameMapService maps = mock(GameMapService.class);
    private final GameRoomNotifier notifier = mock(GameRoomNotifier.class);
    private final GameMatchService matches = new GameMatchService(rooms, maps, notifier);

    @Test
    void cooperativeCreationIsIdempotentAndCannotChangeAnExistingPvpRoom() {
        RoomResponse first = rooms.createCoopRoom(14L);
        assertEquals("COOP", first.getMode());
        assertEquals(first.getRoomId(), rooms.createCoopRoom(14L).getRoomId());
        assertEquals("COOP", rooms.getCurrentRoom(14L).getMode());
        assertNull(rooms.getCurrentRoom(99L));
        rooms.createRoom(15L);
        assertThrows(BusinessException.class, () -> rooms.createCoopRoom(15L));
        assertEquals("PVP", rooms.getCurrentRoom(15L).getMode());
    }

    @Test
    void startUsesBuiltInCooperativeMapAndSnapshotsIncludeSharedAiAndBase() {
        String roomId = start();
        verifyNoInteractions(maps);
        rooms.advanceGames(1000L);
        GameInitResponse owner = rooms.getGameInit(14L, roomId);
        GameInitResponse teammate = rooms.getGameInit(15L, roomId);
        assertEquals(owner.getMatchId(), teammate.getMatchId());
        assertEquals("COOP", owner.getState().getMode());
        assertEquals(8, owner.getState().getEnemyTotal());
        assertEquals(1, owner.getState().getEnemySpawned());
        assertEquals(1, owner.getState().getEnemies().size());
        assertTrue(owner.getState().getBase().isAlive());
        assertEquals(60L, owner.getState().getTanks().get(0).getShieldUntilTick());
        assertEquals(60L, teammate.getState().getTanks().get(1).getShieldUntilTick());
        assertEquals(owner.getState().getEnemies().get(0).getX(), teammate.getState().getEnemies().get(0).getX());
        assertThrows(BusinessException.class, () -> rooms.submitInput(-1L, roomId, owner.getMatchId(), 1L, 0, 1000L, true));
        assertThrows(BusinessException.class, () -> rooms.getGameInit(99L, roomId));
    }

    @Test
    void teammateLeavingAbandonsCooperationWithoutAwardingAPvpWinner() {
        String roomId = start();
        RoomLeaveResult result = rooms.leaveRoom(14L, roomId);
        assertEquals("TEAMMATE_LEFT", result.getEndedGame().getReason());
        assertEquals("ABANDONED", result.getEndedGame().getState().getTeamResult());
        assertNull(result.getEndedGame().getWinnerUserId());
        assertNull(result.getEndedGame().getState().getWinnerUserId());
        assertEquals(0L, result.getEndedGame().getState().getTanks().get(0).getShieldUntilTick());
        assertEquals(0L, result.getEndedGame().getState().getTanks().get(1).getShieldUntilTick());
        assertEquals(15L, result.getRemainingRoomResponse().getOwnerUserId());
        assertEquals("WAITING", result.getRemainingRoomResponse().getStatus());
        assertEquals("COOP", result.getRemainingRoomResponse().getMode());
        assertNull(rooms.getCurrentRoom(14L));
    }

    @Test
    void shortDisconnectRecoversSameMatchButExpiredSeatAbandonsCooperation() {
        String roomId = start();
        String matchId = rooms.getGameInit(14L, roomId).getMatchId();
        rooms.markDisconnected(15L, 1000L);
        rooms.markConnected(15L, 2000L);
        assertEquals(matchId, rooms.getGameInit(15L, roomId).getMatchId());
        rooms.markDisconnected(15L, 3000L);
        List<GameBroadcast> broadcasts = rooms.advanceGames(18000L);
        GameStateResponse end = null;
        for (GameBroadcast broadcast : broadcasts) {
            if ("game:end".equals(broadcast.getMessage().getType())) {
                com.syf.blog.dto.game.play.GameEndResponse response =
                        (com.syf.blog.dto.game.play.GameEndResponse) broadcast.getMessage().getPayload();
                end = response.getState();
            }
        }
        assertNotNull(end);
        assertEquals("ABANDONED", end.getTeamResult());
        assertEquals("TEAMMATE_DISCONNECTED", end.getEndReason());
        assertNull(end.getWinnerUserId());
        assertEquals(0L, end.getTanks().get(0).getShieldUntilTick());
        assertEquals(0L, end.getTanks().get(1).getShieldUntilTick());
        assertEquals("WAITING", rooms.getCurrentRoom(14L).getStatus());
        assertNull(rooms.getCurrentRoom(15L));
    }

    @Test
    void damagedProtectionIsSynchronizedAndShortReconnectDoesNotRefreshIt() {
        String roomId = start();
        GameMatch match = runtime(roomId);
        match.setTick(60L);
        double playerX = match.getTanks().get(0).getX();
        double playerY = match.getTanks().get(0).getY();
        match.getBullets().add(new BulletState(1L, -1L, playerX - 4.0, playerY + 9.6, 3));
        rooms.advanceGames(1000L);

        GameInitResponse owner = rooms.getGameInit(14L, roomId);
        GameInitResponse teammate = rooms.getGameInit(15L, roomId);
        assertEquals(61L, owner.getState().getTick());
        assertEquals(2, owner.getState().getTanks().get(0).getHp());
        assertEquals(79L, owner.getState().getTanks().get(0).getShieldUntilTick());
        assertEquals(79L, teammate.getState().getTanks().get(0).getShieldUntilTick());

        rooms.markDisconnected(14L, 2000L);
        rooms.markConnected(14L, 3000L);
        GameInitResponse recovered = rooms.getGameInit(14L, roomId);
        assertEquals(owner.getMatchId(), recovered.getMatchId());
        assertEquals(79L, recovered.getState().getTanks().get(0).getShieldUntilTick());
        assertEquals(2, recovered.getState().getTanks().get(0).getHp());
    }

    @Test
    void rematchResetsBaseWavesBulletsDamageAndTickWhileKeepingCooperativeMode() {
        String roomId = start();
        GameMatch old = runtime(roomId);
        rooms.advanceGames(1000L);
        old.getBase().setHp(0);
        old.getTanks().get(0).setHp(1);
        rooms.advanceGames(1050L);
        assertEquals("FINISHED", rooms.getRoom(14L, roomId).getStatus());
        assertThrows(BusinessException.class, () -> rooms.rematch(15L, roomId));
        rooms.rematch(14L, roomId);
        rooms.setReady(14L, roomId, true);
        rooms.setReady(15L, roomId, true);
        matches.start(14L, roomId);
        GameInitResponse fresh = rooms.getGameInit(14L, roomId);
        assertNotEquals(old.getMatchId(), fresh.getMatchId());
        assertEquals("COOP", fresh.getState().getMode());
        assertTrue(fresh.getState().getBase().isAlive());
        assertEquals(0L, fresh.getState().getTick());
        assertEquals(0, fresh.getState().getEnemySpawned());
        assertTrue(fresh.getState().getEnemies().isEmpty());
        assertTrue(fresh.getState().getBullets().isEmpty());
        assertTrue(fresh.getState().getDestroyedCells().isEmpty());
        assertEquals(3, fresh.getState().getTanks().get(0).getHp());
        assertEquals(60L, fresh.getState().getTanks().get(0).getShieldUntilTick());
        assertEquals(60L, fresh.getState().getTanks().get(1).getShieldUntilTick());
        assertNull(fresh.getState().getTeamResult());
    }

    private String start() {
        String roomId = rooms.createCoopRoom(14L).getRoomId();
        rooms.joinRoom(15L, roomId);
        rooms.setReady(14L, roomId, true);
        rooms.setReady(15L, roomId, true);
        RoomResponse loading = matches.start(14L, roomId);
        rooms.confirmLoaded(14L, roomId, loading.getMatchId());
        rooms.confirmLoaded(15L, roomId, loading.getMatchId());
        return roomId;
    }

    private GameMatch runtime(String roomId) {
        Map<?, ?> roomMap = (Map<?, ?>) ReflectionTestUtils.getField(rooms, "rooms");
        GameRoom room = (GameRoom) roomMap.get(roomId);
        return room.getMatch();
    }
}
