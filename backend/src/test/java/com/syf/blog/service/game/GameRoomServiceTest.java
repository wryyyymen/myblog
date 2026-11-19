package com.syf.blog.service.game;

import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.runtime.RoomLeaveResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 直接创建房间服务测试内存中的业务行为，不启动 Spring，也不连接数据库。
 */
class GameRoomServiceTest {

    @Test
    void eachMemberCanPrepareAndCancelWithoutChangingTheOtherMember() {
        GameRoomService roomService = new GameRoomService();
        RoomResponse created = roomService.createRoom(14L);
        String roomId = created.getRoomId();
        RoomResponse joined = roomService.joinRoom(15L, roomId);
        assertTrue(joined.getReadyUserIds().isEmpty());

        RoomResponse ownerReady = roomService.setReady(14L, roomId, true);
        assertEquals(List.of(14L), ownerReady.getReadyUserIds());
        assertEquals(3L, ownerReady.getVersion());

        RoomResponse repeatedReady = roomService.setReady(14L, roomId, true);
        assertEquals(ownerReady.getReadyUserIds(), repeatedReady.getReadyUserIds());
        assertEquals(ownerReady.getVersion(), repeatedReady.getVersion());

        RoomResponse bothReady = roomService.setReady(15L, roomId, true);
        assertEquals(List.of(14L, 15L), bothReady.getReadyUserIds());
        assertEquals(4L, bothReady.getVersion());

        RoomResponse ownerCancelled = roomService.setReady(14L, roomId, false);
        assertEquals(List.of(15L), ownerCancelled.getReadyUserIds());
        assertEquals(5L, ownerCancelled.getVersion());

        RoomResponse repeatedCancel = roomService.setReady(14L, roomId, false);
        assertEquals(ownerCancelled.getReadyUserIds(), repeatedCancel.getReadyUserIds());
        assertEquals(ownerCancelled.getVersion(), repeatedCancel.getVersion());
    }

    @Test
    void readySnapshotIsDetachedFromLaterChangesAndCallerEdits() {
        GameRoomService roomService = new GameRoomService();
        RoomResponse created = roomService.createRoom(14L);
        String roomId = created.getRoomId();
        RoomResponse readySnapshot = roomService.setReady(14L, roomId, true);
        RoomResponse secondSnapshot = roomService.createRoom(14L);

        readySnapshot.getReadyUserIds().clear();
        readySnapshot.getReadyUserIds().add(99L);
        assertEquals(List.of(14L), secondSnapshot.getReadyUserIds());
        assertEquals(List.of(14L), roomService.createRoom(14L).getReadyUserIds());

        roomService.setReady(14L, roomId, false);
        assertEquals(List.of(14L), secondSnapshot.getReadyUserIds());
        assertTrue(roomService.createRoom(14L).getReadyUserIds().isEmpty());
    }

    @Test
    void memberChangesResetReadinessButRepeatedJoinDoesNot() {
        GameRoomService roomService = new GameRoomService();
        RoomResponse created = roomService.createRoom(14L);
        String roomId = created.getRoomId();
        roomService.setReady(14L, roomId, true);

        RoomResponse joined = roomService.joinRoom(15L, roomId);
        assertTrue(joined.getReadyUserIds().isEmpty());
        assertEquals(3L, joined.getVersion());

        roomService.setReady(14L, roomId, true);
        RoomResponse bothReady = roomService.setReady(15L, roomId, true);
        RoomResponse repeatedJoin = roomService.joinRoom(15L, roomId);
        assertEquals(bothReady.getReadyUserIds(), repeatedJoin.getReadyUserIds());
        assertEquals(bothReady.getVersion(), repeatedJoin.getVersion());
    }

    @Test
    void outsiderAndCrossRoomOperationsCannotChangeEitherRoom() {
        GameRoomService roomService = new GameRoomService();
        RoomResponse first = roomService.createRoom(14L);
        RoomResponse second = roomService.createRoom(15L);
        roomService.setReady(14L, first.getRoomId(), true);
        roomService.setReady(15L, second.getRoomId(), true);

        // 未加入任何房间的账号与另一个房间的成员，都不能修改目标房间。
        BusinessException outsiderReady = assertThrows(BusinessException.class,
                () -> roomService.setReady(99L, first.getRoomId(), true));
        BusinessException crossRoomReady = assertThrows(BusinessException.class,
                () -> roomService.setReady(15L, first.getRoomId(), false));
        BusinessException outsiderLeave = assertThrows(BusinessException.class,
                () -> roomService.leaveRoom(99L, first.getRoomId()));
        BusinessException crossRoomLeave = assertThrows(BusinessException.class,
                () -> roomService.leaveRoom(15L, first.getRoomId()));

        assertEquals(HttpStatus.FORBIDDEN, outsiderReady.getStatus());
        assertEquals(HttpStatus.FORBIDDEN, crossRoomReady.getStatus());
        assertEquals(HttpStatus.FORBIDDEN, outsiderLeave.getStatus());
        assertEquals(HttpStatus.FORBIDDEN, crossRoomLeave.getStatus());

        RoomResponse firstAfterRejection = roomService.createRoom(14L);
        RoomResponse secondAfterRejection = roomService.createRoom(15L);
        assertEquals(List.of(14L), firstAfterRejection.getMemberUserIds());
        assertEquals(List.of(14L), firstAfterRejection.getReadyUserIds());
        assertEquals(2L, firstAfterRejection.getVersion());
        assertEquals(second.getRoomId(), secondAfterRejection.getRoomId());
        assertEquals(List.of(15L), secondAfterRejection.getReadyUserIds());
        assertEquals(2L, secondAfterRejection.getVersion());
    }

    @Test
    void missingRoomOperationsRejectWithoutDroppingCurrentMembership() {
        GameRoomService roomService = new GameRoomService();
        RoomResponse current = roomService.createRoom(14L);

        BusinessException readyFailure = assertThrows(BusinessException.class,
                () -> roomService.setReady(14L, "missing-room", true));
        BusinessException leaveFailure = assertThrows(BusinessException.class,
                () -> roomService.leaveRoom(14L, "missing-room"));

        assertEquals(HttpStatus.BAD_REQUEST, readyFailure.getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, leaveFailure.getStatus());
        assertEquals(current.getRoomId(), roomService.createRoom(14L).getRoomId());
        assertEquals(1L, roomService.createRoom(14L).getVersion());
    }

    @Test
    void ordinaryMemberLeavesWithoutChangingOwnerAndResetsRemainingReadiness() {
        GameRoomService roomService = new GameRoomService();
        String roomId = roomService.createRoom(14L).getRoomId();
        roomService.joinRoom(15L, roomId);
        roomService.setReady(14L, roomId, true);
        RoomResponse beforeLeave = roomService.setReady(15L, roomId, true);

        RoomLeaveResult result = roomService.leaveRoom(15L, roomId);
        RoomResponse remaining = result.getRemainingRoomResponse();

        assertEquals(roomId, result.getLeftResponse().getRoomId());
        assertEquals(beforeLeave.getVersion() + 1L, result.getLeftResponse().getVersion());
        assertEquals(result.getLeftResponse().getVersion(), remaining.getVersion());
        assertEquals(14L, remaining.getOwnerUserId());
        assertEquals(List.of(14L), remaining.getMemberUserIds());
        assertTrue(remaining.getReadyUserIds().isEmpty());

        // 退出不能改变已生成的快照，修改退出结果也不能改服务内部的名单。
        assertEquals(List.of(14L, 15L), beforeLeave.getMemberUserIds());
        assertEquals(List.of(14L, 15L), beforeLeave.getReadyUserIds());
        remaining.getMemberUserIds().clear();
        remaining.getReadyUserIds().add(99L);
        RoomResponse latest = roomService.createRoom(14L);
        assertEquals(List.of(14L), latest.getMemberUserIds());
        assertTrue(latest.getReadyUserIds().isEmpty());
    }

    @Test
    void departingOwnerTransfersOwnershipToTheRemainingMember() {
        GameRoomService roomService = new GameRoomService();
        String roomId = roomService.createRoom(14L).getRoomId();
        roomService.joinRoom(15L, roomId);
        roomService.setReady(15L, roomId, true);

        RoomLeaveResult result = roomService.leaveRoom(14L, roomId);
        RoomResponse remaining = result.getRemainingRoomResponse();

        assertEquals(15L, remaining.getOwnerUserId());
        assertEquals(List.of(15L), remaining.getMemberUserIds());
        assertTrue(remaining.getReadyUserIds().isEmpty());
        assertEquals(roomId, roomService.createRoom(15L).getRoomId());

        RoomResponse newOwnerReady = roomService.setReady(15L, roomId, true);
        assertEquals(List.of(15L), newOwnerReady.getReadyUserIds());

        RoomResponse departedOwnerNewRoom = roomService.createRoom(14L);
        assertNotEquals(roomId, departedOwnerNewRoom.getRoomId());
        assertEquals(List.of(14L), departedOwnerNewRoom.getMemberUserIds());
    }

    @Test
    void lastDepartureDeletesRoomAndReleasesTheUserIndex() {
        GameRoomService roomService = new GameRoomService();
        RoomResponse original = roomService.createRoom(14L);
        String roomId = original.getRoomId();

        RoomLeaveResult result = roomService.leaveRoom(14L, roomId);

        assertEquals(roomId, result.getLeftResponse().getRoomId());
        assertEquals(2L, result.getLeftResponse().getVersion());
        assertNull(result.getRemainingRoomResponse());
        assertThrows(BusinessException.class, () -> roomService.joinRoom(15L, roomId));
        assertThrows(BusinessException.class, () -> roomService.leaveRoom(14L, roomId));

        RoomResponse newRoom = roomService.createRoom(14L);
        assertNotEquals(roomId, newRoom.getRoomId());
        assertEquals(1L, newRoom.getVersion());
        assertEquals(List.of(14L), newRoom.getMemberUserIds());
        assertTrue(newRoom.getReadyUserIds().isEmpty());
    }

    @Test
    void oldLeaveAndReadyRequestsCannotChangeTheUsersNewRoom() {
        GameRoomService roomService = new GameRoomService();
        String oldRoomId = roomService.createRoom(14L).getRoomId();
        roomService.joinRoom(15L, oldRoomId);
        String newRoomId = roomService.createRoom(16L).getRoomId();
        roomService.leaveRoom(15L, oldRoomId);

        // 退出者可以加入另一房间；随后到达的旧房间请求必须被拒绝。
        roomService.joinRoom(15L, newRoomId);
        RoomResponse beforeOldRequest = roomService.setReady(15L, newRoomId, true);
        assertThrows(BusinessException.class, () -> roomService.leaveRoom(15L, oldRoomId));
        assertThrows(BusinessException.class, () -> roomService.setReady(15L, oldRoomId, false));

        RoomResponse current = roomService.createRoom(15L);
        assertEquals(newRoomId, current.getRoomId());
        assertEquals(List.of(16L, 15L), current.getMemberUserIds());
        assertEquals(List.of(15L), current.getReadyUserIds());
        assertEquals(beforeOldRequest.getVersion(), current.getVersion());
        assertEquals(List.of(14L), roomService.createRoom(14L).getMemberUserIds());
    }

    @Test
    void repeatedDepartureWhileRoomStillExistsFailsWithoutChangingRemainingMember() {
        GameRoomService roomService = new GameRoomService();
        String roomId = roomService.createRoom(14L).getRoomId();
        roomService.joinRoom(15L, roomId);
        roomService.leaveRoom(15L, roomId);
        RoomResponse beforeRetry = roomService.setReady(14L, roomId, true);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> roomService.leaveRoom(15L, roomId));
        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());

        RoomResponse afterRetry = roomService.createRoom(14L);
        assertEquals(List.of(14L), afterRetry.getMemberUserIds());
        assertEquals(List.of(14L), afterRetry.getReadyUserIds());
        assertEquals(beforeRetry.getVersion(), afterRetry.getVersion());
    }

    @Test
    void versionChangesOnlyWhenNewMemberJoins() {
        GameRoomService roomService = new GameRoomService();
        RoomResponse created = roomService.createRoom(14L);
        assertEquals(1L, created.getVersion());

        RoomResponse repeatedCreation = roomService.createRoom(14L);
        assertEquals(1L, repeatedCreation.getVersion());

        RoomResponse joined = roomService.joinRoom(15L, created.getRoomId());
        assertEquals(2L, joined.getVersion());
        assertEquals(List.of(14L, 15L), joined.getMemberUserIds());

        RoomResponse repeatedJoin = roomService.joinRoom(15L, created.getRoomId());
        assertEquals(2L, repeatedJoin.getVersion());

        // 先前拿到的快照不能随着后续成员变化而改变
        assertEquals(1L, created.getVersion());
        assertEquals(List.of(14L), created.getMemberUserIds());
    }

    @Test
    void rejectedJoinDoesNotChangeVersionOrMembership() {
        GameRoomService roomService = new GameRoomService();
        RoomResponse firstRoom = roomService.createRoom(14L);
        RoomResponse secondRoom = roomService.createRoom(15L);

        // 已在其他房间的成员，不能越过检查改动目标房间
        assertThrows(BusinessException.class,
                () -> roomService.joinRoom(15L, firstRoom.getRoomId()));
        RoomResponse latest = roomService.createRoom(14L);
        assertEquals(1L, latest.getVersion());
        assertEquals(List.of(14L), latest.getMemberUserIds());
        assertEquals(secondRoom.getRoomId(), roomService.createRoom(15L).getRoomId());

        // 不存在的房间同样不能留下关联
        assertThrows(BusinessException.class,
                () -> roomService.joinRoom(16L, "missing-room"));
        assertEquals(List.of(16L), roomService.createRoom(16L).getMemberUserIds());
    }

    @Test
    void joiningRoomAllowsRetry() {
        // 直接创建服务对象，每个测试都有自己的空房间 Map
        GameRoomService roomService = new GameRoomService();

        // 测试用的用户 ID，无须实际登录或查询数据库
        Long ownerUserId = 14L;
        Long playerUserId = 15L;

        // 用户 14 创建房间
        RoomResponse createdRoom = roomService.createRoom(ownerUserId);
        String roomId = createdRoom.getRoomId();

        // 用户 15 加入这个房间
        RoomResponse joinedRoom = roomService.joinRoom(playerUserId, roomId);
        List<Long> members = joinedRoom.getMemberUserIds();

        System.out.println(
                "[GameRoomServiceTest] 首次加入后的成员 = " + members);

        // 验证房间号、房主和成员
        assertEquals(roomId, joinedRoom.getRoomId());
        assertEquals(ownerUserId, joinedRoom.getOwnerUserId());
        assertEquals(2, members.size());
        assertTrue(members.contains(ownerUserId));
        assertTrue(members.contains(playerUserId));

        // 房间已经满两人，但已有成员重复加入应正常返回
        RoomResponse repeatedJoin = roomService.joinRoom(playerUserId, roomId);

        assertEquals(roomId, repeatedJoin.getRoomId());
        assertEquals(members, repeatedJoin.getMemberUserIds());

        // 验证 userRoomIds 已记录用户 15 所在的房间：
        // 他再调用创建方法时，应取得已加入的房间
        RoomResponse currentRoom = roomService.createRoom(playerUserId);

        assertEquals(roomId, currentRoom.getRoomId());
        assertEquals(ownerUserId, currentRoom.getOwnerUserId());
    }

    @Test
    void fullRoomRejectsThirdUserWithoutSavingMembership() {
        GameRoomService roomService = new GameRoomService();

        Long ownerUserId = 14L;
        Long secondUserId = 15L;
        Long thirdUserId = 16L;

        // 创建房间并加入第二人，此时成员为 [14, 15]
        RoomResponse createdRoom = roomService.createRoom(ownerUserId);
        String roomId = createdRoom.getRoomId();
        roomService.joinRoom(secondUserId, roomId);

        try {
            // 预期这里抛出 BusinessException，拒绝第三位玩家
            roomService.joinRoom(thirdUserId, roomId);

            // 如果没有抛出异常，就说明满员检查失效，让测试失败
            fail("房间已满，应该拒绝第三位玩家");
        } catch (BusinessException exception) {
            System.out.println(
                    "[GameRoomServiceTest] 收到预期的拒绝原因 = "
                            + exception.getMessage());

            assertEquals(HttpStatus.CONFLICT, exception.getStatus());
            assertEquals(
                    "房间已满，最多允许2名玩家",
                    exception.getMessage());
        }

        // 拒绝后，原房间及成员保持完整，第三人没有被加入
        RoomResponse originalRoom = roomService.createRoom(ownerUserId);
        List<Long> originalMembers = originalRoom.getMemberUserIds();

        assertEquals(roomId, originalRoom.getRoomId());
        assertEquals(ownerUserId, originalRoom.getOwnerUserId());
        assertEquals(2, originalMembers.size());
        assertTrue(originalMembers.contains(ownerUserId));
        assertTrue(originalMembers.contains(secondUserId));
        assertFalse(originalMembers.contains(thirdUserId));

        // 被拒绝的用户没有留下错误的房间关联，仍可创建自己的房间
        RoomResponse thirdUserRoom = roomService.createRoom(thirdUserId);
        List<Long> thirdUserMembers = thirdUserRoom.getMemberUserIds();

        assertNotEquals(roomId, thirdUserRoom.getRoomId());
        assertEquals(thirdUserId, thirdUserRoom.getOwnerUserId());
        assertEquals(1, thirdUserMembers.size());
        assertTrue(thirdUserMembers.contains(thirdUserId));
    }

    @Test
    void creatingRoomAddsOwnerAsItsOnlyMember() {
        GameRoomService roomService = new GameRoomService();
        Long ownerUserId = 14L;

        RoomResponse response = roomService.createRoom(ownerUserId);
        List<Long> memberUserIds = response.getMemberUserIds();

        assertEquals(ownerUserId, response.getOwnerUserId());
        assertEquals(1, memberUserIds.size());
        assertTrue(memberUserIds.contains(ownerUserId));
    }

    @Test
    void repeatedCreationReturnsSameRoomWithoutDuplicateMembers() {
        GameRoomService roomService = new GameRoomService();
        Long ownerUserId = 14L;

        RoomResponse firstResponse = roomService.createRoom(ownerUserId);
        RoomResponse repeatedResponse = roomService.createRoom(ownerUserId);
        List<Long> repeatedMembers = repeatedResponse.getMemberUserIds();

        assertEquals(firstResponse.getRoomId(), repeatedResponse.getRoomId());
        assertEquals(ownerUserId, repeatedResponse.getOwnerUserId());
        assertEquals(1, repeatedMembers.size());
        assertTrue(repeatedMembers.contains(ownerUserId));
    }

    @Test
    void differentUsersKeepSeparateRoomsAndMembers() {
        GameRoomService roomService = new GameRoomService();
        Long firstUserId = 14L;
        Long secondUserId = 15L;

        RoomResponse firstRoom = roomService.createRoom(firstUserId);
        RoomResponse secondRoom = roomService.createRoom(secondUserId);

        // 第二个用户创建房间后，再查询第一个用户的房间，确认保存的数据没有被覆盖。
        RoomResponse firstRoomAgain = roomService.createRoom(firstUserId);
        List<Long> firstMembers = firstRoomAgain.getMemberUserIds();
        List<Long> secondMembers = secondRoom.getMemberUserIds();

        assertNotEquals(firstRoom.getRoomId(), secondRoom.getRoomId());
        assertEquals(firstRoom.getRoomId(), firstRoomAgain.getRoomId());
        assertEquals(firstUserId, firstRoomAgain.getOwnerUserId());
        assertEquals(secondUserId, secondRoom.getOwnerUserId());
        assertEquals(1, firstMembers.size());
        assertTrue(firstMembers.contains(firstUserId));
        assertEquals(1, secondMembers.size());
        assertTrue(secondMembers.contains(secondUserId));
    }

    @Test
    void changingResponseMembersDoesNotChangeStoredRoomOrOtherResponse() {
        GameRoomService roomService = new GameRoomService();
        Long ownerUserId = 14L;
        Long unrelatedUserId = 99L;

        RoomResponse firstResponse = roomService.createRoom(ownerUserId);
        RoomResponse secondResponse = roomService.createRoom(ownerUserId);

        // 模拟调用方修改自己拿到的响应名单；这不应改变房间内部的成员。
        List<Long> firstMembers = firstResponse.getMemberUserIds();
        firstMembers.clear();
        firstMembers.add(unrelatedUserId);

        List<Long> secondMembers = secondResponse.getMemberUserIds();
        assertEquals(1, secondMembers.size());
        assertTrue(secondMembers.contains(ownerUserId));

        // 再从服务读取一次，验证内部保存的成员仍然只有房主。
        RoomResponse latestResponse = roomService.createRoom(ownerUserId);
        List<Long> latestMembers = latestResponse.getMemberUserIds();
        assertEquals(firstResponse.getRoomId(), latestResponse.getRoomId());
        assertEquals(1, latestMembers.size());
        assertTrue(latestMembers.contains(ownerUserId));
    }
}
