package com.syf.blog.controller;

import com.syf.blog.auth.UserPrincipal;
import com.syf.blog.dto.game.room.JoinRoomRequest;
import com.syf.blog.dto.game.room.LeaveRoomRequest;
import com.syf.blog.dto.game.room.PlayerReadyRequest;
import com.syf.blog.dto.game.room.RoomLeftResponse;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.dto.game.room.RoomListResponse;
import com.syf.blog.dto.game.ws.GameWsResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.service.game.GameRoomService;
import com.syf.blog.websocket.game.GameRoomNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 使用真实 Service 和通知器，只替换网络发送组件；不连接数据库。
 */
class GameMessageControllerTest {

    private final GameRoomService roomService = new GameRoomService();
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final GameRoomNotifier notifier = new GameRoomNotifier(messagingTemplate);
    private final GameMessageController controller = new GameMessageController(roomService, notifier);

    @Test
    void listUsesPrincipalAndReturnsASummaryWithoutBroadcastingOrCreatingMembership() {
        String coopId = roomService.createCoopRoom(14L).getRoomId();
        String pvpId = roomService.createRoom(15L).getRoomId();

        GameWsResponse<List<RoomListResponse>> reply = controller.listRooms(new UserPrincipal(99L));

        assertEquals("room:list", reply.getType());
        assertEquals(2, reply.getPayload().size());
        assertNull(roomService.getCurrentRoom(99L));
        List<RoomListResponse> ownerList = controller.listRooms(new UserPrincipal(14L)).getPayload();
        assertEquals(1, ownerList.size());
        assertEquals(pvpId, ownerList.get(0).getRoomId());
        assertEquals(1, roomService.getRoom(14L, coopId).getMemberUserIds().size());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void readyNotifiesOnlyRoomMembersAndUsesTheConnectedUsersIdentity() {
        String roomId = roomService.createRoom(14L).getRoomId();
        roomService.joinRoom(15L, roomId);
        roomService.createRoom(99L);
        PlayerReadyRequest request = readyRequest(roomId, true);

        GameWsResponse<RoomResponse> reply = controller.setReady(request, new UserPrincipal(15L));

        assertEquals("room:ready", reply.getType());
        assertEquals(List.of(15L), reply.getPayload().getReadyUserIds());
        assertEquals(3L, reply.getPayload().getVersion());
        GameWsResponse<?> ownerNotification = captureNotification("14");
        GameWsResponse<?> playerNotification = captureNotification("15");
        assertEquals("room:updated", ownerNotification.getType());
        assertSame(reply.getPayload(), ownerNotification.getPayload());
        assertSame(ownerNotification, playerNotification);
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void cancelReadyBroadcastsTheRemainingPreparedMember() {
        String roomId = roomService.createRoom(14L).getRoomId();
        roomService.joinRoom(15L, roomId);
        roomService.setReady(14L, roomId, true);
        roomService.setReady(15L, roomId, true);
        PlayerReadyRequest request = readyRequest(roomId, false);

        GameWsResponse<RoomResponse> reply = controller.setReady(request, new UserPrincipal(14L));

        assertEquals("room:ready", reply.getType());
        assertEquals(List.of(15L), reply.getPayload().getReadyUserIds());
        assertSame(reply.getPayload(), captureNotification("14").getPayload());
        assertSame(reply.getPayload(), captureNotification("15").getPayload());
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void ordinaryDepartureNotifiesTheDepartingAccountAndUpdatesOnlyRemainingMembers() {
        String roomId = roomService.createRoom(14L).getRoomId();
        roomService.joinRoom(15L, roomId);
        roomService.createRoom(99L);

        GameWsResponse<RoomLeftResponse> reply = controller.leaveRoom(
                leaveRequest(roomId), new UserPrincipal(15L));

        assertEquals("room:left", reply.getType());
        assertEquals(roomId, reply.getPayload().getRoomId());
        assertEquals(3L, reply.getPayload().getVersion());
        GameWsResponse<?> departingNotification = captureNotification("15");
        assertEquals("room:left", departingNotification.getType());
        assertSame(reply.getPayload(), departingNotification.getPayload());

        GameWsResponse<?> remainingNotification = captureNotification("14");
        assertEquals("room:updated", remainingNotification.getType());
        RoomResponse remaining = (RoomResponse) remainingNotification.getPayload();
        assertEquals(List.of(14L), remaining.getMemberUserIds());
        assertEquals(14L, remaining.getOwnerUserId());
        assertTrue(remaining.getReadyUserIds().isEmpty());
        assertEquals(reply.getPayload().getVersion(), remaining.getVersion());
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void ownerDepartureBroadcastsTheTransferredOwnerAndClearedReadiness() {
        String roomId = roomService.createRoom(14L).getRoomId();
        roomService.joinRoom(15L, roomId);
        roomService.setReady(14L, roomId, true);
        roomService.setReady(15L, roomId, true);

        GameWsResponse<RoomLeftResponse> reply = controller.leaveRoom(
                leaveRequest(roomId), new UserPrincipal(14L));

        GameWsResponse<?> departingNotification = captureNotification("14");
        assertEquals("room:left", departingNotification.getType());
        assertSame(reply.getPayload(), departingNotification.getPayload());
        GameWsResponse<?> remainingNotification = captureNotification("15");
        assertEquals("room:updated", remainingNotification.getType());
        RoomResponse remaining = (RoomResponse) remainingNotification.getPayload();
        assertEquals(15L, remaining.getOwnerUserId());
        assertEquals(List.of(15L), remaining.getMemberUserIds());
        assertTrue(remaining.getReadyUserIds().isEmpty());
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void lastDepartureOnlyNotifiesTheDepartingAccount() {
        String roomId = roomService.createRoom(14L).getRoomId();

        GameWsResponse<RoomLeftResponse> reply = controller.leaveRoom(
                leaveRequest(roomId), new UserPrincipal(14L));

        GameWsResponse<?> notification = captureNotification("14");
        assertEquals("room:left", notification.getType());
        assertSame(reply.getPayload(), notification.getPayload());
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void rejectedReadyAndLeaveDoNotEmitNotifications() {
        String roomId = roomService.createRoom(14L).getRoomId();
        assertThrows(BusinessException.class,
                () -> controller.setReady(readyRequest(roomId, true), new UserPrincipal(15L)));
        assertThrows(BusinessException.class,
                () -> controller.leaveRoom(leaveRequest(roomId), new UserPrincipal(15L)));
        assertThrows(BusinessException.class,
                () -> controller.setReady(readyRequest("missing-room", true), new UserPrincipal(14L)));
        assertThrows(BusinessException.class,
                () -> controller.leaveRoom(leaveRequest("missing-room"), new UserPrincipal(14L)));

        verifyNoInteractions(messagingTemplate);
        assertEquals(List.of(14L), roomService.createRoom(14L).getMemberUserIds());
        assertTrue(roomService.createRoom(14L).getReadyUserIds().isEmpty());
    }

    @Test
    void createNotifiesOwnerAndKeepsExistingReplyType() {
        UserPrincipal owner = new UserPrincipal(14L);
        GameWsResponse<RoomResponse> reply = controller.createRoom(owner);

        assertEquals("room:created", reply.getType());
        assertEquals(List.of(14L), reply.getPayload().getMemberUserIds());
        assertEquals(1L, reply.getPayload().getVersion());

        GameWsResponse<?> notification = captureNotification("14");
        assertEquals("room:updated", notification.getType());
        assertSame(reply.getPayload(), notification.getPayload());
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void joinNotifiesBothMembersWithTheSameSnapshotButNotOtherRooms() {
        GameWsResponse<RoomResponse> created = controller.createRoom(new UserPrincipal(14L));
        controller.createRoom(new UserPrincipal(99L));
        clearInvocations(messagingTemplate);

        JoinRoomRequest request = new JoinRoomRequest();
        request.setRoomId(created.getPayload().getRoomId());
        GameWsResponse<RoomResponse> reply = controller.joinRoom(request, new UserPrincipal(15L));

        assertEquals("room:joined", reply.getType());
        assertEquals(List.of(14L, 15L), reply.getPayload().getMemberUserIds());
        assertEquals(2L, reply.getPayload().getVersion());

        GameWsResponse<?> ownerNotification = captureNotification("14");
        GameWsResponse<?> playerNotification = captureNotification("15");
        assertEquals("room:updated", ownerNotification.getType());
        assertSame(reply.getPayload(), ownerNotification.getPayload());
        assertSame(ownerNotification, playerNotification);
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void fullRoomRejectsOutsiderWithoutSendingAnyRoomNotification() {
        RoomResponse created = roomService.createRoom(14L);
        roomService.joinRoom(15L, created.getRoomId());
        JoinRoomRequest request = new JoinRoomRequest();
        request.setRoomId(created.getRoomId());

        BusinessException failure = assertThrows(BusinessException.class,
                () -> controller.joinRoom(request, new UserPrincipal(16L)));
        GameWsResponse<String> error = controller.handleBusinessException(failure);

        assertEquals("error", error.getType());
        assertEquals("房间已满，最多允许2名玩家", error.getPayload());
        verifyNoInteractions(messagingTemplate);
        assertEquals(2L, roomService.createRoom(14L).getVersion());
    }

    @Test
    void missingRoomDoesNotSendAnyRoomNotification() {
        JoinRoomRequest request = new JoinRoomRequest();
        request.setRoomId("missing-room");

        assertThrows(BusinessException.class,
                () -> controller.joinRoom(request, new UserPrincipal(14L)));
        verifyNoInteractions(messagingTemplate);
    }

    private GameWsResponse<?> captureNotification(String userName) {
        ArgumentCaptor<GameWsResponse<?>> captor = ArgumentCaptor.captor();
        verify(messagingTemplate).convertAndSendToUser(
                eq(userName), eq("/queue/replies"), captor.capture());
        GameWsResponse<?> notification = captor.getValue();
        return notification;
    }

    private PlayerReadyRequest readyRequest(String roomId, boolean ready) {
        PlayerReadyRequest request = new PlayerReadyRequest();
        request.setRoomId(roomId);
        request.setReady(ready);
        return request;
    }

    private LeaveRoomRequest leaveRequest(String roomId) {
        LeaveRoomRequest request = new LeaveRoomRequest();
        request.setRoomId(roomId);
        return request;
    }
}
