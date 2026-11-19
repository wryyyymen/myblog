package com.syf.blog.service.game;

import com.syf.blog.dto.game.room.RoomListResponse;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.runtime.GameRoom;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 大厅列表只操作真实房间服务的内存数据，不启动 Spring 或访问数据库。 */
class GameRoomListTest {
    private final GameRoomService rooms = new GameRoomService();

    @Test
    void returnsStableSummariesForCoopAndPvpRooms() {
        RoomResponse coop = rooms.createCoopRoom(14L);
        RoomResponse pvp = rooms.createRoom(15L);
        List<String> expectedIds = new ArrayList<String>();
        expectedIds.add(coop.getRoomId());
        expectedIds.add(pvp.getRoomId());
        Collections.sort(expectedIds);

        List<RoomListResponse> listed = rooms.listAvailableRooms(99L);

        assertEquals(2, listed.size());
        for (int index = 0; index < listed.size(); index++) {
            RoomListResponse room = listed.get(index);
            assertEquals(expectedIds.get(index), room.getRoomId());
            assertEquals(1, room.getMemberCount());
            assertEquals(2, room.getMaxMembers());
            assertEquals(1L, room.getVersion());
            if (room.getOwnerUserId().equals(14L)) {
                assertEquals("COOP", room.getMode());
            } else {
                assertEquals(15L, room.getOwnerUserId());
                assertEquals("PVP", room.getMode());
            }
        }
    }

    @Test
    void excludesOwnEmptyAndFullRooms() {
        rooms.createCoopRoom(99L);
        String emptyId = rooms.createCoopRoom(14L).getRoomId();
        runtime(emptyId).getMemberUserIds().clear();
        String fullId = rooms.createCoopRoom(15L).getRoomId();
        rooms.joinRoom(16L, fullId);
        String availableId = rooms.createCoopRoom(17L).getRoomId();

        List<RoomListResponse> listed = rooms.listAvailableRooms(99L);

        assertEquals(1, listed.size());
        assertEquals(availableId, listed.get(0).getRoomId());
    }

    @Test
    void excludesLoadingPlayingAndFinishedEvenWhenASeatAppearsAvailable() {
        String[] statuses = {"LOADING", "PLAYING", "FINISHED"};
        for (int index = 0; index < statuses.length; index++) {
            String roomId = rooms.createCoopRoom(14L + index).getRoomId();
            // 隔离状态过滤条件，不能只因这些阶段通常双人满员就认为已覆盖。
            runtime(roomId).setStatus(statuses[index]);
        }
        String waitingId = rooms.createCoopRoom(20L).getRoomId();

        List<RoomListResponse> listed = rooms.listAvailableRooms(99L);

        assertEquals(1, listed.size());
        assertEquals(waitingId, listed.get(0).getRoomId());
    }

    @Test
    void offlineOwnerIsHiddenAndReconnectMakesTheRoomVisibleAgain() {
        String roomId = rooms.createCoopRoom(14L).getRoomId();
        rooms.markDisconnected(14L, 1000L);
        long offlineVersion = rooms.getRoom(14L, roomId).getVersion();

        assertTrue(rooms.listAvailableRooms(99L).isEmpty());
        assertEquals(offlineVersion, rooms.getRoom(14L, roomId).getVersion());
        assertNotNull(rooms.getCurrentRoom(14L), "查询不应擅自清理尚在保留期的席位");

        rooms.markConnected(14L, 2000L);
        assertEquals(roomId, rooms.listAvailableRooms(99L).get(0).getRoomId());
    }

    @Test
    void joiningTheLastSeatRemovesTheRoomAndOldSummaryDoesNotChange() {
        String roomId = rooms.createCoopRoom(14L).getRoomId();
        List<RoomListResponse> original = rooms.listAvailableRooms(99L);
        RoomListResponse oldSummary = original.get(0);
        rooms.setReady(14L, roomId, true);
        rooms.joinRoom(15L, roomId);

        assertTrue(rooms.listAvailableRooms(99L).isEmpty());
        assertEquals(1, oldSummary.getMemberCount());
        assertEquals(1L, oldSummary.getVersion());
        assertThrows(UnsupportedOperationException.class, original::clear);

        rooms.leaveRoom(15L, roomId);
        RoomListResponse reopened = rooms.listAvailableRooms(99L).get(0);
        assertEquals(1, reopened.getMemberCount());
        assertTrue(reopened.getVersion() > oldSummary.getVersion());
    }

    @Test
    void lookupDoesNotCreateRoomsMembershipOrChangeReadyState() {
        assertTrue(rooms.listAvailableRooms(99L).isEmpty());
        assertNull(rooms.getCurrentRoom(99L));
        RoomResponse created = rooms.createCoopRoom(14L);
        RoomResponse prepared = rooms.setReady(14L, created.getRoomId(), true);

        rooms.listAvailableRooms(99L);
        rooms.listAvailableRooms(14L);

        RoomResponse after = rooms.getRoom(14L, created.getRoomId());
        assertEquals(prepared.getVersion(), after.getVersion());
        assertEquals(List.of(14L), after.getMemberUserIds());
        assertEquals(List.of(14L), after.getReadyUserIds());
        assertNull(rooms.getCurrentRoom(99L));
        assertEquals(1, roomMap().size());
    }

    @Test
    void requiresAValidConnectedIdentityFromTheCaller() {
        BusinessException missing = assertThrows(BusinessException.class, () -> rooms.listAvailableRooms(null));
        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatus());
        assertThrows(BusinessException.class, () -> rooms.listAvailableRooms(0L));
        assertThrows(BusinessException.class, () -> rooms.listAvailableRooms(-1L));
    }

    @Test
    void limitsTheStableSortedResultToOneHundredRooms() {
        List<String> expectedIds = new ArrayList<String>();
        for (long userId = 1000L; userId < 1105L; userId++) {
            expectedIds.add(rooms.createCoopRoom(userId).getRoomId());
        }
        Collections.sort(expectedIds);

        List<RoomListResponse> listed = rooms.listAvailableRooms(99L);

        assertEquals(100, listed.size());
        for (int index = 0; index < listed.size(); index++) {
            assertEquals(expectedIds.get(index), listed.get(index).getRoomId());
        }
        assertEquals(105, roomMap().size(), "列表数量上限不能删除真实房间");
    }

    @Test
    void concurrentJoiningAndListingOnlyExposesCompleteAvailableSummaries() throws Exception {
        String roomId = rooms.createCoopRoom(14L).getRoomId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> writing = executor.submit(new JoinLeaveTask(rooms, roomId, start));
            Future<Void> reading = executor.submit(new ListRoomsTask(rooms, roomId, start));
            start.countDown();
            writing.get(5, TimeUnit.SECONDS);
            reading.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, rooms.listAvailableRooms(99L).size());
        assertFalse(rooms.getRoom(14L, roomId).getMemberUserIds().contains(15L));
    }

    private GameRoom runtime(String roomId) {
        return (GameRoom) roomMap().get(roomId);
    }

    private Map<?, ?> roomMap() {
        return (Map<?, ?>) ReflectionTestUtils.getField(rooms, "rooms");
    }

    @RequiredArgsConstructor
    private static class JoinLeaveTask implements Callable<Void> {
        private final GameRoomService rooms;
        private final String roomId;
        private final CountDownLatch start;

        @Override
        public Void call() throws Exception {
            start.await();
            for (int index = 0; index < 25; index++) {
                rooms.joinRoom(15L, roomId);
                rooms.leaveRoom(15L, roomId);
            }
            return null;
        }
    }

    @RequiredArgsConstructor
    private static class ListRoomsTask implements Callable<Void> {
        private final GameRoomService rooms;
        private final String roomId;
        private final CountDownLatch start;

        @Override
        public Void call() throws Exception {
            start.await();
            for (int index = 0; index < 50; index++) {
                List<RoomListResponse> listed = rooms.listAvailableRooms(99L);
                assertTrue(listed.size() <= 1);
                if (!listed.isEmpty()) {
                    assertEquals(roomId, listed.get(0).getRoomId());
                    assertEquals(1, listed.get(0).getMemberCount());
                    assertTrue(listed.get(0).getVersion() >= 1L);
                }
            }
            return null;
        }
    }
}
