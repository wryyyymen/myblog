package com.syf.blog.service.game;

import com.syf.blog.exception.BusinessException;
import org.springframework.http.HttpStatus;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.dto.game.room.RoomLeftResponse;
import com.syf.blog.dto.game.room.RoomListResponse;
import com.syf.blog.game.runtime.GameRoom;
import com.syf.blog.game.runtime.RoomLeaveResult;
import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.dto.game.play.GameEndResponse;
import com.syf.blog.dto.game.play.GameInitResponse;
import com.syf.blog.dto.game.play.GameRulesResponse;
import com.syf.blog.dto.game.play.GameStateResponse;
import com.syf.blog.dto.game.play.TankResponse;
import com.syf.blog.dto.game.play.BulletResponse;
import com.syf.blog.dto.game.ws.GameWsResponse;
import com.syf.blog.game.engine.GameMovementEngine;
import com.syf.blog.game.engine.GameCombatEngine;
import com.syf.blog.game.engine.GameCoopEngine;
import com.syf.blog.dto.game.play.EnemyResponse;
import com.syf.blog.dto.game.play.BaseResponse;
import com.syf.blog.game.runtime.GameBroadcast;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.PlayerInput;
import com.syf.blog.game.runtime.TankState;
import com.syf.blog.game.runtime.BulletState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.Collections;

/**
 * 负责房间创建、加入、准备和主动退出。
 */
@Service
public class GameRoomService {
        // 房间 ID → 房间对象
        private final Map<String, GameRoom> rooms = new HashMap<String, GameRoom>();

        // 用户 ID → 该用户当前所在的房间 ID
        private final Map<Long, String> userRoomIds = new HashMap<Long, String>();

        // 双人游戏：房主也占一个位置
        private static final int MAX_ROOM_MEMBERS = 2;

        private static final int MAX_ROOM_LIST_SIZE = 100;

        private static final long LOADING_TIMEOUT_MILLIS = 30_000L;

        private static final long RECONNECT_WINDOW_MILLIS = 15_000L;

        // 纯规则对象，不访问数据库、不发送网络消息。
        private final GameMovementEngine movementEngine = new GameMovementEngine();
        private final GameCombatEngine combatEngine = new GameCombatEngine();
        private final GameCoopEngine coopEngine = new GameCoopEngine();

        /**
         * 大厅只读查询：复制可加入房间摘要，不创建房间、不修改版本或清理成员。
         * 与加入和离线清理共用服务锁，避免取得一半更新的成员状态。
         */
        public synchronized List<RoomListResponse> listAvailableRooms(Long userId) {
                if (userId == null || userId <= 0L) {
                        throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录后查看房间列表");
                }
                List<String> roomIds = new ArrayList<String>(rooms.keySet());
                Collections.sort(roomIds);
                List<RoomListResponse> responses = new ArrayList<RoomListResponse>();
                for (String roomId : roomIds) {
                        GameRoom room = rooms.get(roomId);
                        int memberCount = room.getMemberUserIds().size();
                        if (!"WAITING".equals(room.getStatus()) || memberCount < 1
                                        || memberCount >= MAX_ROOM_MEMBERS
                                        || room.getMemberUserIds().contains(userId)
                                        || !room.getConnectedUserIds().contains(room.getOwnerUserId())) {
                                continue;
                        }
                        RoomListResponse response = new RoomListResponse(room.getRoomId(),
                                        room.getOwnerUserId(), memberCount, MAX_ROOM_MEMBERS,
                                        room.getMode(), room.getVersion());
                        responses.add(response);
                        if (responses.size() >= MAX_ROOM_LIST_SIZE) {
                                break;
                        }
                }
                System.out.println("[GameRoomService.listAvailableRooms] userId = " + userId
                                + "，可加入房间数量 = " + responses.size());
                return List.copyOf(responses);
        }

        /** 新合作入口；已有其他模式房间时要求先退出，不能暗中改变一局的规则。 */
        public synchronized RoomResponse createCoopRoom(Long userId) {
                String currentRoomId = userRoomIds.get(userId);
                if (currentRoomId != null) {
                        GameRoom existing = rooms.get(currentRoomId);
                        if (!"COOP".equals(existing.getMode())) {
                                throw new BusinessException(HttpStatus.CONFLICT, "请先退出原对战房间，再创建合作房间");
                        }
                        return createResponse(existing);
                }
                RoomResponse created = createRoom(userId);
                GameRoom room = rooms.get(created.getRoomId());
                room.setMode("COOP");
                System.out.println("[GameRoomService.createCoopRoom] 合作房间 = " + room.getRoomId());
                return createResponse(room);
        }

        /**
         * 将当前用户加入指定房间
         *
         * @param roomId 用户想加入的房间号，之后从请求正文取得
         * @param userId 当前用户的 ID,之后由Controller从 Principal 中取得
         * @return
         */

        public synchronized RoomResponse joinRoom(Long userId, String roomId) {
                System.out.println(
                                "[GameRoomService.joinRoom] 开始处理，userId = "
                                                + userId + "，roomId = " + roomId);
                // 1.按房间号找到的服务器保存的房间对象
                GameRoom room = rooms.get(roomId);

                if (room == null) {
                        System.out.println("[GameRoomService.joinRoom] 拒绝加入：房间不存在");
                        throw new BusinessException(HttpStatus.BAD_REQUEST, "房间不存在");
                }

                // 2. 查询这个用户是否已经在某个房间里
                String existingRoomId = userRoomIds.get(userId);

                System.out.println(
                                "[GameRoomService.joinRoom] 用户当前所在房间 = "
                                                + existingRoomId);
                // 已在其他房间时，不能直接加入新房间
                if (existingRoomId != null && !existingRoomId.equals(roomId)) {
                        System.out.println("[GameRoomService.joinRoom] 拒绝加入：用户已在其他房间");

                        throw new BusinessException(
                                        HttpStatus.CONFLICT,
                                        "你已经在其他房间中，请先退出原房间");
                }

                // 3. 取得目标房间内部的成员集合
                Set<Long> memberUserIds = room.getMemberUserIds();

                // 先判断是否已经加入，再判断是否满员
                if (memberUserIds.contains(userId)) {
                        System.out.println(
                                        "[GameRoomService.joinRoom] 用户已在该房间，本次直接返回");
                } else {
                        requireWaiting(room);
                        // 只有新成员才需要检查剩余位置
                        if (memberUserIds.size() >= MAX_ROOM_MEMBERS) {
                                System.out.println("[GameRoomService.joinRoom] 拒绝加入：房间已满");

                                throw new BusinessException(
                                                HttpStatus.CONFLICT,
                                                "房间已满，最多允许"+ MAX_ROOM_MEMBERS+"名玩家");
                        }

                        // 4. 检查全部通过后，才修改服务器中的数据
                        memberUserIds.add(userId);
                        room.getConnectedUserIds().add(userId);
                        userRoomIds.put(userId, roomId);

                        // 队友发生变化后，不能沿用原成员对上一份名单的准备确认
                        room.getReadyUserIds().clear();

                        // 与成员修改一起持有同一把锁，确保并发加入得到递增的版本
                        long nextVersion = room.getVersion() + 1L;
                        room.setVersion(nextVersion);

                        System.out.println(
                                        "[GameRoomService.joinRoom] 加入成功，当前成员 = "
                                                        + memberUserIds);

                        System.out.println(
                                        "[GameRoomService.joinRoom] 已保存用户关联，userId = "
                                                        + userId + "，roomId = " + userRoomIds.get(userId));
                }

                // 5. 复制成员名单，构造本次响应
                List<Long> responseMemberUserIds = new ArrayList<Long>(memberUserIds);
                RoomResponse response = createResponse(room);

                System.out.println(
                                "[GameRoomService.joinRoom] 返回成员 = "
                                                + responseMemberUserIds);

                return response;

        }

        /**
         * 为当前用户创建房间。
         * 创建时将房主加入成员集合；如果用户已经拥有房间，则返回原来的房间。
         * 返回的成员列表是本次查询的副本，不与房间内部的集合共用。
         *
         * @param userId 从连接身份取得的可信用户 ID
         * @return 创建或查询得到的房间信息
         */
        public synchronized RoomResponse createRoom(Long userId) {
                System.out.println("[GameRoomService.createRoom] 收到 userId = " + userId);

                // 第一次查询；看看这个用户是否已经有房间
                String existingRoomId = userRoomIds.get(userId);

                GameRoom room;

                if (existingRoomId != null) {
                        // 已经有房间：通过房间 ID 取得原来的房间对象
                        room = rooms.get(existingRoomId);

                        System.out.println(
                                        "[GameRoomService.createRoom] 复用原房间 = "
                                                        + existingRoomId);
                } else {
                        // UUID 用于生成一个随机的房间号
                        UUID uuid = UUID.randomUUID();
                        String roomId = uuid.toString();

                        // 创建房间对象，此时成员集合还是空的
                        room = new GameRoom(roomId, userId);

                        System.out.println(
                                        "[GameRoomService.createRoom] 新建房间，roomId = "
                                                        + roomId + "，ownerUserId = " + userId);

                        // 取得房间内部的成员集合，将创建者加入为第一位成员
                        Set<Long> initialMembers = room.getMemberUserIds();
                        initialMembers.add(userId);
                        room.getConnectedUserIds().add(userId);

                        System.out.println(
                                        "[GameRoomService.createRoom] 已把房主加入成员，userId = "
                                                        + userId);

                        // 成员初始化完成后，保存房间及用户与房间的关联
                        rooms.put(roomId, room);
                        userRoomIds.put(userId, roomId);
                }
                System.out.println(
                                "[GameRoomService.createRoom] 房间数量 = " + rooms.size()
                                                + "，用户关联数量 = " + userRoomIds.size());

                // 从房间对象读取当前成员
                Set<Long> memberUserIds = room.getMemberUserIds();

                // 复制一份名单作为响应；修改返回的 List 不会影响房间内部的 Set
                List<Long> responseMemberUserIds = new ArrayList<Long>(memberUserIds);

                System.out.println(
                                "[GameRoomService.createRoom] 返回成员 = " + responseMemberUserIds);

                RoomResponse response = createResponse(room);

                return response;
        }

        /** 只能修改自己的准备状态，重复设置同一状态不会增加版本。 */
        public synchronized RoomResponse setReady(Long userId, String roomId, boolean ready) {
                System.out.println("[GameRoomService.setReady] userId = " + userId
                                + "，roomId = " + roomId + "，ready = " + ready);

                GameRoom room = requireMemberRoom(userId, roomId);
                requireWaiting(room);
                requireConnectedMember(room, userId);
                Set<Long> readyUserIds = room.getReadyUserIds();
                boolean changed;
                if (ready) {
                        changed = readyUserIds.add(userId);
                } else {
                        changed = readyUserIds.remove(userId);
                }

                if (changed) {
                        long nextVersion = room.getVersion() + 1L;
                        room.setVersion(nextVersion);
                }

                RoomResponse response = createResponse(room);
                System.out.println("[GameRoomService.setReady] 已准备成员 = "
                                + response.getReadyUserIds() + "，version = " + response.getVersion());
                return response;
        }

        /**
         * 主动退出并释放用户索引；房主退出时转移给剩余成员，空房立即回收。
         * roomId 必须是该用户当前房间，迟到的旧房间请求不能移除新房间的席位。
         */
        public synchronized RoomLeaveResult leaveRoom(Long userId, String roomId) {
                System.out.println("[GameRoomService.leaveRoom] userId = " + userId
                                + "，roomId = " + roomId);
                GameRoom room = requireMemberRoom(userId, roomId);

                GameEndResponse endedGame = null;
                if (room.getMatch() != null && !"FINISHED".equals(room.getStatus())) {
                        GameMatch match = room.getMatch();
                        Long winnerUserId = null;
                        for (Long memberUserId : room.getMemberUserIds()) {
                                if (!memberUserId.equals(userId)) {
                                        winnerUserId = memberUserId;
                                }
                        }
                        match.setFinished(true);
                        for (TankState tank : match.getTanks()) {
                                tank.setShieldUntilTick(0L);
                        }
                        match.setWinnerUserId(winnerUserId);
                        String reason = "PLAYER_LEFT";
                        if ("COOP".equals(room.getMode())) {
                                winnerUserId = null;
                                match.setWinnerUserId(null);
                                match.setTeamResult("ABANDONED");
                                reason = "TEAMMATE_LEFT";
                        }
                        match.setEndReason(reason);
                        GameStateResponse finalState = createGameState(room, "FINISHED", System.currentTimeMillis());
                        endedGame = new GameEndResponse(roomId, room.getMatchId(), reason, userId,
                                        finalState, winnerUserId);
                }
                clearGame(room);

                Set<Long> memberUserIds = room.getMemberUserIds();
                memberUserIds.remove(userId);
                room.getConnectedUserIds().remove(userId);
                room.getDisconnectedUntilMillis().remove(userId);
                userRoomIds.remove(userId);
                room.getReadyUserIds().clear();
                long nextVersion = room.getVersion() + 1L;
                room.setVersion(nextVersion);

                RoomResponse remainingRoomResponse = null;
                if (memberUserIds.isEmpty()) {
                        rooms.remove(roomId);
                        System.out.println("[GameRoomService.leaveRoom] 空房已回收，roomId = " + roomId);
                } else {
                        if (room.getOwnerUserId().equals(userId)) {
                                Long nextOwnerUserId = memberUserIds.iterator().next();
                                room.setOwnerUserId(nextOwnerUserId);
                                System.out.println("[GameRoomService.leaveRoom] 新房主 = " + nextOwnerUserId);
                        }
                        remainingRoomResponse = createResponse(room);
                }

                RoomLeftResponse leftResponse = new RoomLeftResponse(roomId, nextVersion);
                RoomLeaveResult result = new RoomLeaveResult(leftResponse, remainingRoomResponse, endedGame);
                System.out.println("[GameRoomService.leaveRoom] 已退出，剩余成员 = "
                                + memberUserIds + "，version = " + nextVersion);
                return result;
        }

        /** 仅由本类同步方法调用，检查请求指定的房间与服务端成员关系一致。 */
        private GameRoom requireMemberRoom(Long userId, String roomId) {
                GameRoom room = rooms.get(roomId);
                if (room == null) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, "房间不存在");
                }

                String currentRoomId = userRoomIds.get(userId);
                if (!roomId.equals(currentRoomId) || !room.getMemberUserIds().contains(userId)) {
                        throw new BusinessException(HttpStatus.FORBIDDEN, "你不是该房间的成员");
                }
                return room;
        }

        /** 在服务锁内复制两份名单，已发送的快照不会随着后续操作而改变。 */
        private RoomResponse createResponse(GameRoom room) {
                String roomId = room.getRoomId();
                Long ownerUserId = room.getOwnerUserId();
                List<Long> memberUserIds = new ArrayList<Long>(room.getMemberUserIds());
                long version = room.getVersion();
                List<Long> readyUserIds = new ArrayList<Long>(room.getReadyUserIds());
                RoomResponse response = new RoomResponse(roomId, ownerUserId, memberUserIds, version,
                                readyUserIds, room.getStatus(), room.getMatchId(),
                                new ArrayList<Long>(room.getConnectedUserIds()), room.getMode());
                return response;
        }

        /** 开局第一段只变更内存状态，地图查询在释放锁后进行。 */
        public synchronized RoomResponse beginStart(Long userId, String roomId, long nowMillis) {
                GameRoom room = requireMemberRoom(userId, roomId);
                requireWaiting(room);
                if (!room.getOwnerUserId().equals(userId)) {
                        throw new BusinessException(HttpStatus.FORBIDDEN, "只有房主可以开始游戏");
                }
                if (room.getMemberUserIds().size() != MAX_ROOM_MEMBERS
                                || !room.getReadyUserIds().containsAll(room.getMemberUserIds())
                                || !room.getConnectedUserIds().containsAll(room.getMemberUserIds())) {
                        throw new BusinessException(HttpStatus.CONFLICT, "需要两名玩家都准备后才能开始");
                }
                String matchId = UUID.randomUUID().toString();
                room.setMatchId(matchId);
                room.setStatus("LOADING");
                room.setLoadingDeadlineMillis(nowMillis + LOADING_TIMEOUT_MILLIS);
                room.setVersion(room.getVersion() + 1L);
                System.out.println("[GameRoomService.beginStart] roomId = " + roomId + "，matchId = " + matchId);
                return createResponse(room);
        }

        /** 安装本局地图副本；已取消开局的异步/迟到结果不能恢复旧局。 */
        public synchronized GameInitResponse installMap(String roomId, String matchId, GameMapResponse map) {
                GameRoom room = rooms.get(roomId);
                if (room == null || !"LOADING".equals(room.getStatus()) || !matchId.equals(room.getMatchId())) {
                        throw new BusinessException(HttpStatus.CONFLICT, "本次开局已取消");
                }
                List<Long> members = new ArrayList<Long>(room.getMemberUserIds());
                GameMatch match;
                if ("COOP".equals(room.getMode())) {
                        match = coopEngine.createMatch(roomId, matchId, members);
                } else {
                        match = movementEngine.createMatch(roomId, matchId, map, members);
                }
                room.setMatch(match);
                return createGameInit(room, System.currentTimeMillis());
        }

        /** 只回滚指定的加载局，不触碰后来创建的新局。 */
        public synchronized RoomResponse cancelLoading(String roomId, String matchId) {
                GameRoom room = rooms.get(roomId);
                if (room == null || !"LOADING".equals(room.getStatus()) || !matchId.equals(room.getMatchId())) {
                        return null;
                }
                clearGame(room);
                room.getReadyUserIds().clear();
                room.setVersion(room.getVersion() + 1L);
                return createResponse(room);
        }

        /** 只有本局双方都完成加载，才开始服务端物理推进。 */
        public synchronized GameStateResponse confirmLoaded(Long userId, String roomId, String matchId) {
                GameRoom room = requireMemberRoom(userId, roomId);
                requireConnectedMember(room, userId);
                GameMatch match = requireMatch(room, matchId);
                if ("PLAYING".equals(room.getStatus()) || "FINISHED".equals(room.getStatus())) {
                        return createGameState(room, room.getStatus(), System.currentTimeMillis());
                }
                if (!"LOADING".equals(room.getStatus())) {
                        throw new BusinessException(HttpStatus.CONFLICT, "当前房间不在加载阶段");
                }
                match.getLoadedUserIds().add(userId);
                if (!match.getLoadedUserIds().containsAll(room.getMemberUserIds())) {
                        return null;
                }
                room.setStatus("PLAYING");
                room.setLoadingDeadlineMillis(0L);
                room.setVersion(room.getVersion() + 1L);
                System.out.println("[GameRoomService.confirmLoaded] 双方已加载，matchId = " + matchId);
                return createGameState(room, "PLAYING", System.currentTimeMillis());
        }

        /** 仅接收完整方向状态；位置和移动距离由固定 tick 决定。 */
        public synchronized void submitInput(Long userId, String roomId, String matchId,
                        long seq, Integer direction, long nowMillis) {
                submitInput(userId, roomId, matchId, seq, direction, nowMillis, false);
        }

        public synchronized void submitInput(Long userId, String roomId, String matchId,
                        long seq, Integer direction, long nowMillis, boolean fireHeld) {
                GameRoom room = requireMemberRoom(userId, roomId);
                requireConnectedMember(room, userId);
                GameMatch match = requireMatch(room, matchId);
                if (!"PLAYING".equals(room.getStatus())) {
                        throw new BusinessException(HttpStatus.CONFLICT, "当前对局未在运行");
                }
                if (seq <= 0L || direction != null && (direction < 0 || direction > 3)) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, "输入序号或方向不正确");
                }
                TankState ownTank = null;
                for (TankState tank : match.getTanks()) {
                        if (tank.getUserId().equals(userId)) {
                                ownTank = tank;
                                break;
                        }
                }
                if (ownTank == null || ownTank.getHp() <= 0 || seq <= ownTank.getLastInputSeq()) {
                        return;
                }
                PlayerInput previous = match.getInputs().get(userId);
                // 连续输入只保留最新一份；过密的移动刷新直接丢弃，停止指令始终允许。
                boolean releasingFire = previous != null && previous.isFireHeld() && !fireHeld;
                if (previous != null && direction != null && !releasingFire
                                && nowMillis - previous.getReceivedAtMillis() < 10L) {
                        return;
                }
                PlayerInput input = new PlayerInput(seq, direction, nowMillis);
                input.setFireHeld(fireHeld);
                match.getInputs().put(userId, input);
                ownTank.setLastInputSeq(seq);
                if (previous == null || !Objects.equals(previous.getDirection(), direction)) {
                        System.out.println("[GameRoomService.submitInput] userId = " + userId
                                        + "，direction = " + direction + "，seq = " + seq);
                }
        }

        public synchronized RoomResponse getRoom(Long userId, String roomId) {
                GameRoom room = requireMemberRoom(userId, roomId);
                return createResponse(room);
        }

        /** 重连只查询已有成员关系，没有房间时返回 null，不隐式创建。 */
        public synchronized RoomResponse getCurrentRoom(Long userId) {
                String roomId = userRoomIds.get(userId);
                if (roomId == null) {
                        return null;
                }
                GameRoom room = requireMemberRoom(userId, roomId);
                return createResponse(room);
        }

        public synchronized GameInitResponse getGameInit(Long userId, String roomId) {
                GameRoom room = requireMemberRoom(userId, roomId);
                if (room.getMatch() == null) {
                        return null;
                }
                return createGameInit(room, System.currentTimeMillis());
        }

        /** 单个调度线程每 50ms 调用。锁内只做内存规则与快照，锁外投递。 */
        public synchronized List<GameBroadcast> advanceGames(long nowMillis) {
                List<GameBroadcast> broadcasts = new ArrayList<GameBroadcast>();
                // 断线期限到达时可能删除房间，因此遍历房间引用的副本。
                List<GameRoom> roomSnapshot = new ArrayList<GameRoom>(rooms.values());
                for (GameRoom room : roomSnapshot) {
                        expireDisconnectedMembers(room, nowMillis, broadcasts);
                        if (!rooms.containsKey(room.getRoomId())) {
                                continue;
                        }
                        if ("LOADING".equals(room.getStatus()) && nowMillis >= room.getLoadingDeadlineMillis()) {
                                clearGame(room);
                                room.getReadyUserIds().clear();
                                room.setVersion(room.getVersion() + 1L);
                                RoomResponse response = createResponse(room);
                                broadcasts.add(new GameBroadcast(response.getMemberUserIds(),
                                                new GameWsResponse<RoomResponse>("room:updated", response)));
                        } else if ("PLAYING".equals(room.getStatus())) {
                                GameMatch match = room.getMatch();
                                if ("COOP".equals(match.getMode())) {
                                        coopEngine.step(match, nowMillis);
                                }
                                movementEngine.step(match, nowMillis);
                                combatEngine.step(match, nowMillis);
                                if (match.isFinished()) {
                                        room.setStatus("FINISHED");
                                        room.getReadyUserIds().clear();
                                        room.setVersion(room.getVersion() + 1L);
                                        match.getInputs().clear();
                                        GameStateResponse state = createGameState(room, "FINISHED", nowMillis);
                                        GameEndResponse end = new GameEndResponse(room.getRoomId(), room.getMatchId(),
                                                        match.getEndReason(), null, state, match.getWinnerUserId());
                                        List<Long> members = new ArrayList<Long>(room.getMemberUserIds());
                                        broadcasts.add(new GameBroadcast(members,
                                                        new GameWsResponse<GameEndResponse>("game:end", end)));
                                        RoomResponse response = createResponse(room);
                                        broadcasts.add(new GameBroadcast(members,
                                                        new GameWsResponse<RoomResponse>("room:updated", response)));
                                        System.out.println("[GameRoomService.advanceGames] 对局结束，matchId = "
                                                        + match.getMatchId() + "，winnerUserId = " + match.getWinnerUserId()
                                                        + "，reason = " + match.getEndReason());
                                } else if (match.getTick() % 2L == 0L) {
                                        GameStateResponse state = createGameState(room, "PLAYING", nowMillis);
                                        List<Long> members = new ArrayList<Long>(room.getMemberUserIds());
                                        broadcasts.add(new GameBroadcast(members,
                                                        new GameWsResponse<GameStateResponse>("game:state", state)));
                                }
                        }
                }
                return broadcasts;
        }

        /** 同账号第一条游戏连接建立后调用，取消仍有效的离线保留期限。 */
        public synchronized List<GameBroadcast> markConnected(Long userId, long nowMillis) {
                List<GameBroadcast> broadcasts = new ArrayList<GameBroadcast>();
                String roomId = userRoomIds.get(userId);
                GameRoom room = rooms.get(roomId);
                if (room == null) {
                        return broadcasts;
                }

                // 不能利用定时线程尚未执行的间隙，在保留期限之后取回过期席位。
                expireDisconnectedMembers(room, nowMillis, broadcasts);
                if (!roomId.equals(userRoomIds.get(userId))) {
                        return broadcasts;
                }
                boolean changed = room.getConnectedUserIds().add(userId);
                room.getDisconnectedUntilMillis().remove(userId);
                if (changed) {
                        room.setVersion(room.getVersion() + 1L);
                        appendRoomUpdated(room, broadcasts);
                        System.out.println("[GameRoomService.markConnected] 已恢复连接，userId = " + userId);
                }
                return broadcasts;
        }

        /** 同账号最后一条游戏连接关闭后调用，立即停输入，席位最多保留 15 秒。 */
        public synchronized List<GameBroadcast> markDisconnected(Long userId, long nowMillis) {
                List<GameBroadcast> broadcasts = new ArrayList<GameBroadcast>();
                String roomId = userRoomIds.get(userId);
                GameRoom room = rooms.get(roomId);
                if (room == null || !room.getConnectedUserIds().remove(userId)) {
                        return broadcasts;
                }

                room.getReadyUserIds().remove(userId);
                room.getDisconnectedUntilMillis().put(userId, nowMillis + RECONNECT_WINDOW_MILLIS);
                if (room.getMatch() != null) {
                        room.getMatch().getInputs().remove(userId);
                }
                if ("LOADING".equals(room.getStatus())) {
                        clearGame(room);
                        room.getReadyUserIds().clear();
                }
                room.setVersion(room.getVersion() + 1L);
                appendRoomUpdated(room, broadcasts);
                System.out.println("[GameRoomService.markDisconnected] 暂时离线，userId = " + userId
                                + "，保留席位 15 秒");
                return broadcasts;
        }

        /** 由调用方持有本服务锁，按同一个 nowMillis 批量移除本房间已过期的离线成员。 */
        private void expireDisconnectedMembers(GameRoom room, long nowMillis, List<GameBroadcast> broadcasts) {
                List<Long> expiredUsers = new ArrayList<Long>();
                for (Map.Entry<Long, Long> entry : room.getDisconnectedUntilMillis().entrySet()) {
                        if (nowMillis >= entry.getValue()) {
                                expiredUsers.add(entry.getKey());
                        }
                }
                if (expiredUsers.isEmpty()) {
                        return;
                }

                List<Long> originalMembers = new ArrayList<Long>(room.getMemberUserIds());
                if ("PLAYING".equals(room.getStatus()) && room.getMatch() != null) {
                        Long winnerUserId = null;
                        for (Long memberUserId : originalMembers) {
                                if (room.getConnectedUserIds().contains(memberUserId)) {
                                        winnerUserId = memberUserId;
                                }
                        }
                        String reason = winnerUserId == null ? "ABANDONED" : "PLAYER_DISCONNECTED";
                        GameMatch match = room.getMatch();
                        if ("COOP".equals(room.getMode())) {
                                winnerUserId = null;
                                reason = "TEAMMATE_DISCONNECTED";
                                match.setTeamResult("ABANDONED");
                        }
                        match.setFinished(true);
                        for (TankState tank : match.getTanks()) {
                                tank.setShieldUntilTick(0L);
                        }
                        match.setWinnerUserId(winnerUserId);
                        match.setEndReason(reason);
                        match.getInputs().clear();
                        GameStateResponse finalState = createGameState(room, "FINISHED", nowMillis);
                        GameEndResponse end = new GameEndResponse(room.getRoomId(), room.getMatchId(), reason,
                                        expiredUsers.get(0), finalState, winnerUserId);
                        broadcasts.add(new GameBroadcast(originalMembers,
                                        new GameWsResponse<GameEndResponse>("game:end", end)));
                }

                clearGame(room);
                room.getReadyUserIds().clear();
                for (Long userId : expiredUsers) {
                        room.getMemberUserIds().remove(userId);
                        room.getConnectedUserIds().remove(userId);
                        room.getDisconnectedUntilMillis().remove(userId);
                        if (room.getRoomId().equals(userRoomIds.get(userId))) {
                                userRoomIds.remove(userId);
                        }
                }
                room.setVersion(room.getVersion() + 1L);

                for (Long userId : expiredUsers) {
                        RoomLeftResponse response = new RoomLeftResponse(room.getRoomId(), room.getVersion());
                        List<Long> recipient = new ArrayList<Long>();
                        recipient.add(userId);
                        broadcasts.add(new GameBroadcast(recipient,
                                        new GameWsResponse<RoomLeftResponse>("room:left", response)));
                }
                if (room.getMemberUserIds().isEmpty()) {
                        rooms.remove(room.getRoomId());
                } else {
                        if (!room.getMemberUserIds().contains(room.getOwnerUserId())) {
                                Long nextOwnerUserId = room.getMemberUserIds().iterator().next();
                                room.setOwnerUserId(nextOwnerUserId);
                        }
                        appendRoomUpdated(room, broadcasts);
                }
                System.out.println("[GameRoomService.expireDisconnectedMembers] 已回收离线席位，roomId = "
                                + room.getRoomId() + "，userIds = " + expiredUsers);
        }

        private void appendRoomUpdated(GameRoom room, List<GameBroadcast> broadcasts) {
                RoomResponse response = createResponse(room);
                broadcasts.add(new GameBroadcast(response.getMemberUserIds(),
                                new GameWsResponse<RoomResponse>("room:updated", response)));
        }

        private void requireConnectedMember(GameRoom room, Long userId) {
                if (!room.getConnectedUserIds().contains(userId)) {
                        throw new BusinessException(HttpStatus.CONFLICT, "游戏连接已断开，请重新连接");
                }
        }

        private void requireWaiting(GameRoom room) {
                if (!"WAITING".equals(room.getStatus())) {
                        throw new BusinessException(HttpStatus.CONFLICT, "当前房间不在等待阶段，请先结束或重开本局");
                }
        }

        /** 结算页由房主返回等待；下一次开始使用新的地图、坦克、血量和输入。 */
        public synchronized RoomResponse rematch(Long userId, String roomId) {
                GameRoom room = requireMemberRoom(userId, roomId);
                if (!room.getOwnerUserId().equals(userId)) {
                        throw new BusinessException(HttpStatus.FORBIDDEN, "只有房主可以重新开局");
                }
                if (!"FINISHED".equals(room.getStatus()) || room.getMemberUserIds().size() != MAX_ROOM_MEMBERS) {
                        throw new BusinessException(HttpStatus.CONFLICT, "需要双人对局结束后才能重开");
                }
                clearGame(room);
                room.getReadyUserIds().clear();
                room.setVersion(room.getVersion() + 1L);
                System.out.println("[GameRoomService.rematch] 已返回等待，roomId = " + roomId);
                return createResponse(room);
        }

        private GameMatch requireMatch(GameRoom room, String matchId) {
                if (!Objects.equals(room.getMatchId(), matchId) || room.getMatch() == null) {
                        throw new BusinessException(HttpStatus.CONFLICT, "对局已失效，请同步当前房间");
                }
                return room.getMatch();
        }

        private void clearGame(GameRoom room) {
                room.setMatch(null);
                room.setMatchId(null);
                room.setStatus("WAITING");
                room.setLoadingDeadlineMillis(0L);
        }

        private GameStateResponse createGameState(GameRoom room, String status, long nowMillis) {
                GameMatch match = room.getMatch();
                List<TankResponse> tanks = new ArrayList<TankResponse>();
                for (TankState tank : match.getTanks()) {
                        tanks.add(TankResponse.from(tank));
                }
                List<BulletResponse> bullets = new ArrayList<BulletResponse>();
                for (BulletState bullet : match.getBullets()) {
                        bullets.add(BulletResponse.from(bullet));
                }
                List<Integer> destroyedCells = new ArrayList<Integer>(match.getDestroyedCells());
                List<EnemyResponse> enemies = new ArrayList<EnemyResponse>();
                int defeated = 0;
                for (TankState enemy : match.getEnemies()) {
                        enemies.add(EnemyResponse.from(enemy));
                        if (enemy.getHp() <= 0) defeated++;
                }
                int enemyTotal = "COOP".equals(match.getMode()) ? GameCoopEngine.ENEMY_TOTAL : 0;
                GameStateResponse response = new GameStateResponse(room.getRoomId(), room.getMatchId(), status,
                                match.getTick(), nowMillis, tanks, bullets, destroyedCells,
                                match.getWinnerUserId(), match.getEndReason(), match.getMode(), enemies,
                                BaseResponse.from(match.getBase()), enemyTotal, match.getEnemySpawned(), defeated,
                                match.getTeamResult());
                return response;
        }

        private GameInitResponse createGameInit(GameRoom room, long nowMillis) {
                GameMatch match = room.getMatch();
                GameRulesResponse rules = new GameRulesResponse(GameMovementEngine.MOVE_SPEED,
                                GameMovementEngine.STEP_MILLIS, GameMovementEngine.INPUT_TIMEOUT_MILLIS,
                                GameCombatEngine.INITIAL_HP, GameCombatEngine.BULLET_SPEED,
                                GameCombatEngine.FIRE_COOLDOWN_MILLIS, GameCombatEngine.MATCH_DURATION_MILLIS);
                GameStateResponse state = createGameState(room, room.getStatus(), nowMillis);
                GameInitResponse response = new GameInitResponse(room.getRoomId(), room.getMatchId(),
                                room.getVersion(), match.getMap(), rules, state);
                return response;
        }

}
