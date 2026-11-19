package com.syf.blog.websocket.game;

import com.syf.blog.auth.CurrentUserService;
import com.syf.blog.auth.GameHandshakeHandler;
import com.syf.blog.auth.GameHandshakeInterceptor;
import com.syf.blog.config.GameWebSocketConfig;
import com.syf.blog.controller.GameMessageController;
import com.syf.blog.controller.GamePlayController;
import com.syf.blog.domain.BlogUser;
import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.service.GameMapService;
import com.syf.blog.service.game.GameMatchService;
import com.syf.blog.service.game.GameRoomService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用真实随机端口验证 STOMP、JSON、用户定向推送和服务端移动。
 * 身份与地图只在本测试上下文中模拟，不连接开发数据库。
 * 此上下文没有启用定时任务，测试显式推进 tick，避免依赖机器运行速度。
 */
@SpringBootTest(
        classes = GameMovementIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.sql.init.mode=never",
                "spring.jpa.hibernate.ddl-auto=validate",
                "debug=false"
        })
class GameMovementIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({GameWebSocketConfig.class, GameHandshakeInterceptor.class, GameHandshakeHandler.class,
            GameRoomService.class, GameMatchService.class, GameRoomNotifier.class,
            GamePresenceListener.class, GameMessageController.class, GamePlayController.class})
    static class TestApplication {

        @Bean
        CurrentUserService currentUserService() {
            CurrentUserService users = mock(CurrentUserService.class);
            when(users.requireLogin(any(HttpServletRequest.class))).thenAnswer(new TestLoginAnswer());
            return users;
        }

        @Bean
        GameMapService gameMapService() {
            GameMapService maps = mock(GameMapService.class);
            when(maps.getById(1L)).thenReturn(openMap());
            return maps;
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private GameRoomService rooms;

    @Autowired
    private GameMatchService matches;

    @Autowired
    private GameMapService maps;

    @Test
    void anonymousHandshakeIsRejectedBeforeStompCanStart() {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> HttpClient.newHttpClient().newWebSocketBuilder()
                        .buildAsync(endpoint(), new RecordingListener())
                        .get(5, TimeUnit.SECONDS));

        WebSocketHandshakeException handshake = assertInstanceOf(
                WebSocketHandshakeException.class, failure.getCause());
        assertEquals(401, handshake.getResponse().statusCode());
    }

    @Test
    void currentRoomLookupDoesNotCreateARoomAndOnlyReturnsTheConnectedUsersMembership() throws Exception {
        try (TestPeer owner = connect(101L); TestPeer outsider = connect(102L)) {
            owner.send("/app/room/current", "{}");
            assertTrue(owner.awaitType("room:current").get("payload").isNull());

            owner.send("/app/room/create", "{}");
            JsonNode created = owner.awaitType("room:created").get("payload");
            String roomId = created.get("roomId").asString();
            // 查询前没有隐式创建，否则这里已经不是新房间的初始版本。
            assertEquals(1L, created.get("version").asLong());
            owner.send("/app/room/current", "{}");
            JsonNode current = owner.awaitType("room:current").get("payload");
            assertEquals(roomId, current.get("roomId").asString());
            assertEquals(101L, current.get("ownerUserId").asLong());
            assertEquals(1, current.get("connectedUserIds").size());

            outsider.send("/app/room/current", "{}");
            assertTrue(outsider.awaitType("room:current").get("payload").isNull());
            outsider.send("/app/game/sync", "{\"roomId\":\"" + roomId + "\"}");
            JsonNode denied = outsider.awaitType("error");
            assertTrue(denied.get("payload").asString().contains("成员"));

            // 被拒绝的跨房查询不会污染原房间，未加入用户仍没有房间。
            outsider.send("/app/room/current", "{}");
            assertTrue(outsider.awaitType("room:current").get("payload").isNull());
            owner.send("/app/room/leave", "{\"roomId\":\"" + roomId + "\"}");
            owner.awaitType("room:left");
        }
    }

    @Test
    void realStompListsAvailableRoomsOnlyForTheRequesterAndLastSeatJoinRemovesTheEntry() throws Exception {
        try (TestPeer owner = connect(411L); TestPeer otherOwner = connect(412L); TestPeer visitor = connect(413L)) {
            owner.send("/app/room/create-coop", "{}");
            String coopId = owner.awaitType("room:created").get("payload").get("roomId").asString();
            otherOwner.send("/app/room/create", "{}");
            String pvpId = otherOwner.awaitType("room:created").get("payload").get("roomId").asString();

            visitor.send("/app/room/list", "{}");
            JsonNode listed = visitor.awaitType("room:list").get("payload");
            assertTrue(listed.isArray());
            JsonNode coop = listedRoom(listed, coopId);
            assertNotNull(coop);
            assertEquals(6, coop.size());
            assertEquals(411L, coop.get("ownerUserId").asLong());
            assertEquals(1, coop.get("memberCount").asInt());
            assertEquals(2, coop.get("maxMembers").asInt());
            assertEquals("COOP", coop.get("mode").asString());
            assertEquals(1L, coop.get("version").asLong());
            assertEquals("PVP", listedRoom(listed, pvpId).get("mode").asString());

            // 查询只向当前连接回复；其他已连接账号不会收到大厅列表推送。
            owner.pingBarrier();
            owner.assertNoPendingMessage("room:list");
            otherOwner.pingBarrier();
            otherOwner.assertNoPendingMessage("room:list");
            visitor.send("/app/room/current", "{}");
            assertTrue(visitor.awaitType("room:current").get("payload").isNull());

            owner.send("/app/room/list", "{}");
            JsonNode ownView = owner.awaitType("room:list").get("payload");
            assertNull(listedRoom(ownView, coopId));
            assertNotNull(listedRoom(ownView, pvpId));

            visitor.send("/app/room/join", "{\"roomId\":\"" + coopId + "\"}");
            visitor.awaitType("room:joined");
            otherOwner.send("/app/room/list", "{}");
            JsonNode fullView = otherOwner.awaitType("room:list").get("payload");
            assertNull(listedRoom(fullView, coopId));

            visitor.send("/app/room/leave", "{\"roomId\":\"" + coopId + "\"}");
            visitor.awaitType("room:left");
            otherOwner.send("/app/room/list", "{}");
            JsonNode reopened = otherOwner.awaitType("room:list").get("payload");
            assertNotNull(listedRoom(reopened, coopId));
            owner.send("/app/room/leave", "{\"roomId\":\"" + coopId + "\"}");
            owner.awaitType("room:left");
            otherOwner.send("/app/room/leave", "{\"roomId\":\"" + pvpId + "\"}");
            otherOwner.awaitType("room:left");
        }
    }

    @Test
    void reconnectWithinGracePeriodRestoresSameMatchAndContinuesInputSequence() throws Exception {
        try (TestPeer owner = connect(211L); TestPeer originalPlayer = connect(212L)) {
            owner.send("/app/room/create", "{}");
            String roomId = owner.awaitType("room:created").get("payload").get("roomId").asString();
            originalPlayer.send("/app/room/join", "{\"roomId\":\"" + roomId + "\"}");
            originalPlayer.awaitType("room:joined");
            owner.send("/app/player/ready", readyBody(roomId));
            owner.awaitType("room:ready");
            originalPlayer.send("/app/player/ready", readyBody(roomId));
            originalPlayer.awaitType("room:ready");
            owner.send("/app/room/start", "{\"roomId\":\"" + roomId + "\"}");
            String matchId = owner.awaitType("room:started").get("payload").get("matchId").asString();
            owner.awaitType("game:init");
            originalPlayer.awaitType("game:init");
            owner.send("/app/game/loaded", loadedBody(roomId, matchId));
            originalPlayer.send("/app/game/loaded", loadedBody(roomId, matchId));
            owner.awaitType("game:start");
            originalPlayer.awaitType("game:start");

            originalPlayer.send("/app/player/input", inputBody(roomId, matchId, 9L, 2));
            originalPlayer.pingBarrier();
            matches.tick();
            matches.tick();
            double previousX = rooms.getGameInit(211L, roomId).getState().getTanks().get(1).getX();
            long beforeDisconnectVersion = rooms.getRoom(211L, roomId).getVersion();
            originalPlayer.close();
            JsonNode offlineRoom = owner.awaitConnectedRoom(
                    "PLAYING", 211L, 2, 1, beforeDisconnectVersion + 1L).get("payload");
            assertEquals(211L, offlineRoom.get("connectedUserIds").get(0).asLong());

            // 离线马上清空按键；即使最后一次移动输入尚未超过500ms，也不会继续走。
            rooms.advanceGames(System.currentTimeMillis() + 100L);
            assertEquals(previousX, rooms.getGameInit(211L, roomId).getState().getTanks().get(1).getX());

            try (TestPeer recovered = connect(212L)) {
                owner.awaitConnectedRoom("PLAYING", 211L, 2, 2, offlineRoom.get("version").asLong() + 1L);
                recovered.send("/app/room/current", "{}");
                JsonNode current = recovered.awaitType("room:current").get("payload");
                assertEquals(roomId, current.get("roomId").asString());
                assertEquals(matchId, current.get("matchId").asString());
                assertEquals("PLAYING", current.get("status").asString());
                recovered.send("/app/game/sync", "{\"roomId\":\"" + roomId + "\"}");
                JsonNode recoveredInit = recovered.awaitType("game:init").get("payload");
                assertEquals(matchId, recoveredInit.get("matchId").asString());
                assertEquals(9L, tank(recoveredInit.get("state"), 212L).get("lastInputSeq").asLong());

                recovered.send("/app/game/loaded", loadedBody(roomId, matchId));
                recovered.awaitType("game:start");
                recovered.send("/app/player/input", inputBody(roomId, matchId, 10L, 2));
                recovered.send("/app/player/input", inputBody(roomId, matchId, 9L, 3));
                recovered.pingBarrier();
                matches.tick();
                matches.tick();
                JsonNode latest = recovered.awaitType("game:state").get("payload");
                assertEquals(10L, tank(latest, 212L).get("lastInputSeq").asLong());
                assertTrue(tank(latest, 212L).get("x").asDouble() < previousX);

                recovered.send("/app/room/leave", "{\"roomId\":\"" + roomId + "\"}");
                recovered.awaitType("room:left");
                owner.awaitRoom("WAITING", 211L, 1);
                owner.send("/app/room/leave", "{\"roomId\":\"" + roomId + "\"}");
                owner.awaitType("room:left");
            }
        }
    }

    @Test
    void realStompRejectsDirectBrokerSubscriptionsAndForgedUserNotifications() throws Exception {
        try (TestPeer subscriber = connect(311L)) {
            subscriber.sendFrame("SUBSCRIBE\nid:forbidden\ndestination:/queue/replies\n\n\0");
            subscriber.awaitProtocolRejection();
        }
        try (TestPeer victim = connect(313L); TestPeer sender = connect(312L)) {
            sender.send("/user/313/queue/replies", "{\"type\":\"forged:notification\",\"payload\":{}}");
            sender.awaitProtocolRejection();
            // 被攻击账号仍能正常通信；伪造消息既不能到达其订阅，也不能污染后续回复。
            victim.pingBarrier();
            victim.assertNoPendingMessage("forged:notification");
        }
    }

    @Test
    void twoPlayersLoadTheSameMatchMoveTogetherAndReturnToRoomAfterLeaving() throws Exception {
        try (TestPeer owner = connect(14L); TestPeer player = connect(15L)) {
            owner.send("/app/room/create", "{}");
            JsonNode createdRoom = owner.awaitType("room:created").get("payload");
            String roomId = createdRoom.get("roomId").asString();
            assertEquals("WAITING", createdRoom.get("status").asString());
            assertEquals(1L, createdRoom.get("version").asLong());

            player.send("/app/room/join", "{\"roomId\":\"" + roomId + "\"}");
            JsonNode joinedRoom = player.awaitType("room:joined").get("payload");
            assertEquals(2, joinedRoom.get("memberUserIds").size());

            owner.send("/app/player/ready", readyBody(roomId));
            owner.awaitType("room:ready");
            player.send("/app/player/ready", readyBody(roomId));
            player.awaitType("room:ready");
            owner.send("/app/room/start", "{\"roomId\":\"" + roomId + "\"}");

            JsonNode startedRoom = owner.awaitType("room:started").get("payload");
            assertEquals("LOADING", startedRoom.get("status").asString());
            String matchId = startedRoom.get("matchId").asString();
            JsonNode ownerInit = owner.awaitType("game:init").get("payload");
            JsonNode playerInit = player.awaitType("game:init").get("payload");
            assertEquals(matchId, ownerInit.get("matchId").asString());
            assertEquals(ownerInit, playerInit);
            assertEquals(24, ownerInit.get("map").get("tileSize").asInt());
            assertEquals(2, ownerInit.get("state").get("tanks").size());
            assertEquals("LOADING", ownerInit.get("state").get("status").asString());

            // 一个玩家确认加载还不能开始；同连接 ping 作为前一指令已处理的屏障。
            owner.send("/app/game/loaded", loadedBody(roomId, matchId));
            owner.pingBarrier();
            assertEquals("LOADING", rooms.createRoom(14L).getStatus());

            player.send("/app/game/loaded", loadedBody(roomId, matchId));
            JsonNode initialState = owner.awaitType("game:start").get("payload");
            JsonNode secondInitialState = player.awaitType("game:start").get("payload");
            assertEquals(initialState, secondInitialState);
            assertEquals("PLAYING", initialState.get("status").asString());
            double initialX = tank(initialState, 14L).get("x").asDouble();

            owner.send("/app/player/input", "{\"roomId\":\"" + roomId
                    + "\",\"matchId\":\"" + matchId + "\",\"seq\":1,\"direction\":3,\"fireHeld\":true}");
            owner.pingBarrier();
            matches.tick();
            matches.tick();

            JsonNode ownerState = owner.awaitType("game:state").get("payload");
            JsonNode playerState = player.awaitType("game:state").get("payload");
            assertEquals(ownerState, playerState);
            assertEquals(2L, ownerState.get("tick").asLong());
            assertTrue(tank(ownerState, 14L).get("x").asDouble() > initialX);
            assertEquals(1L, tank(ownerState, 14L).get("lastInputSeq").asLong());
            assertEquals(3, tank(ownerState, 14L).get("hp").asInt());
            assertTrue(ownerState.get("bullets").size() > 0);
            assertTrue(ownerState.get("destroyedCells").isEmpty());
            assertTrue(ownerState.get("winnerUserId").isNull());

            // 主动退出结束移动演示，剩余玩家回到等待房间且成为房主。
            owner.send("/app/room/leave", "{\"roomId\":\"" + roomId + "\"}");
            JsonNode ended = player.awaitType("game:end").get("payload");
            assertEquals(matchId, ended.get("matchId").asString());
            assertEquals("PLAYER_LEFT", ended.get("reason").asString());
            assertEquals(14L, ended.get("leavingUserId").asLong());
            assertEquals("FINISHED", ended.get("state").get("status").asString());
            owner.awaitType("room:left");
            JsonNode remainingRoom = player.awaitRoom("WAITING", 15L, 1).get("payload");
            assertTrue(remainingRoom.get("readyUserIds").isEmpty());
            assertTrue(remainingRoom.get("matchId").isNull());

            player.send("/app/room/leave", "{\"roomId\":\"" + roomId + "\"}");
            player.awaitType("room:left");
        }
    }

    @Test
    void realStompFireInputProducesWinnerAndOwnerCanStartAFreshRematch() throws Exception {
        when(maps.getById(1L)).thenReturn(corridorMap());
        try (TestPeer owner = connect(24L); TestPeer player = connect(25L)) {
            owner.send("/app/room/create", "{}");
            String roomId = owner.awaitType("room:created").get("payload").get("roomId").asString();
            player.send("/app/room/join", "{\"roomId\":\"" + roomId + "\"}");
            player.awaitType("room:joined");
            owner.send("/app/player/ready", readyBody(roomId));
            owner.awaitType("room:ready");
            player.send("/app/player/ready", readyBody(roomId));
            player.awaitType("room:ready");
            owner.send("/app/room/start", "{\"roomId\":\"" + roomId + "\"}");
            String oldMatch = owner.awaitType("room:started").get("payload").get("matchId").asString();
            owner.awaitType("game:init");
            player.awaitType("game:init");
            owner.send("/app/game/loaded", loadedBody(roomId, oldMatch));
            player.send("/app/game/loaded", loadedBody(roomId, oldMatch));
            owner.awaitType("game:start");
            player.awaitType("game:start");

            // 每轮确认完整按键状态已送达，再显式推进两步；没有等待真实对局时长。
            for (int inputNumber = 1; inputNumber <= 60; inputNumber++) {
                owner.send("/app/player/input", "{\"roomId\":\"" + roomId
                        + "\",\"matchId\":\"" + oldMatch + "\",\"seq\":" + inputNumber
                        + ",\"direction\":null,\"fireHeld\":true}");
                owner.pingBarrier();
                matches.tick();
                matches.tick();
                if ("FINISHED".equals(rooms.getRoom(24L, roomId).getStatus())) break;
            }

            assertEquals("FINISHED", rooms.getRoom(24L, roomId).getStatus());
            JsonNode ownerEnd = owner.awaitType("game:end").get("payload");
            JsonNode playerEnd = player.awaitType("game:end").get("payload");
            assertEquals(ownerEnd, playerEnd);
            assertEquals("DESTROYED", ownerEnd.get("reason").asString());
            assertEquals(24L, ownerEnd.get("winnerUserId").asLong());
            assertEquals(0, tank(ownerEnd.get("state"), 25L).get("hp").asInt());

            owner.send("/app/room/rematch", "{\"roomId\":\"" + roomId + "\"}");
            JsonNode waiting = owner.awaitType("room:rematched").get("payload");
            assertEquals("WAITING", waiting.get("status").asString());
            assertTrue(waiting.get("matchId").isNull());
            assertTrue(waiting.get("readyUserIds").isEmpty());
            player.awaitRoomVersion("WAITING", 24L, 2, waiting.get("version").asLong());

            owner.send("/app/player/ready", readyBody(roomId));
            owner.awaitType("room:ready");
            player.send("/app/player/ready", readyBody(roomId));
            player.awaitType("room:ready");
            owner.send("/app/room/start", "{\"roomId\":\"" + roomId + "\"}");
            String newMatch = owner.awaitType("room:started").get("payload").get("matchId").asString();
            assertNotEquals(oldMatch, newMatch);
            JsonNode fresh = owner.awaitType("game:init").get("payload").get("state");
            assertEquals(3, tank(fresh, 24L).get("hp").asInt());
            assertEquals(3, tank(fresh, 25L).get("hp").asInt());
            assertEquals(0L, fresh.get("tick").asLong());
            assertTrue(fresh.get("bullets").isEmpty());
            assertTrue(fresh.get("destroyedCells").isEmpty());

            owner.send("/app/room/leave", "{\"roomId\":\"" + roomId + "\"}");
            owner.awaitType("room:left");
            player.send("/app/room/leave", "{\"roomId\":\"" + roomId + "\"}");
            player.awaitType("room:left");
        } finally {
            when(maps.getById(1L)).thenReturn(openMap());
        }
    }

    @Test
    void realCooperativeStompSharesAiBaseResultsAndRematchBetweenTwoPlayers() throws Exception {
        try (TestPeer owner = connect(214L); TestPeer teammate = connect(215L)) {
            owner.send("/app/room/create-coop", "{}");
            JsonNode created = owner.awaitType("room:created").get("payload");
            assertEquals("COOP", created.get("mode").asString());
            String roomId = created.get("roomId").asString();
            teammate.send("/app/room/join", "{\"roomId\":\"" + roomId + "\"}");
            assertEquals("COOP", teammate.awaitType("room:joined").get("payload").get("mode").asString());
            owner.send("/app/player/ready", readyBody(roomId));
            owner.awaitType("room:ready");
            teammate.send("/app/player/ready", readyBody(roomId));
            teammate.awaitType("room:ready");
            owner.send("/app/room/start", "{\"roomId\":\"" + roomId + "\"}");
            JsonNode init = owner.awaitType("game:init").get("payload");
            String matchId = init.get("matchId").asString();
            assertEquals(init, teammate.awaitType("game:init").get("payload"));
            assertEquals(8, init.get("state").get("enemyTotal").asInt());
            assertEquals(60L, tank(init.get("state"), 214L).get("shieldUntilTick").asLong());
            assertEquals(60L, tank(init.get("state"), 215L).get("shieldUntilTick").asLong());
            owner.send("/app/game/loaded", loadedBody(roomId, matchId));
            teammate.send("/app/game/loaded", loadedBody(roomId, matchId));
            assertEquals(owner.awaitType("game:start").get("payload"), teammate.awaitType("game:start").get("payload"));

            owner.send("/app/player/input", inputBody(roomId, matchId, 1L, 0));
            owner.pingBarrier();
            matches.tick();
            matches.tick();
            JsonNode state = owner.awaitType("game:state").get("payload");
            assertEquals(state, teammate.awaitType("game:state").get("payload"));
            assertEquals(1, state.get("enemies").size());
            assertEquals(60L, tank(state, 214L).get("shieldUntilTick").asLong());
            assertTrue(state.get("base").get("alive").asBoolean());
            assertTrue(tank(state, 214L).get("y").asDouble() < tank(init.get("state"), 214L).get("y").asDouble());

            // 让真实 AI 持续进攻；测试显式加速 tick，无需等待三分钟真实时间。
            for (int tick = 0; tick < 3600 && !"FINISHED".equals(rooms.getRoom(214L, roomId).getStatus()); tick++) {
                matches.tick();
            }
            JsonNode ownerEnd = owner.awaitType("game:end").get("payload");
            JsonNode teammateEnd = teammate.awaitType("game:end").get("payload");
            assertEquals(ownerEnd, teammateEnd);
            assertEquals("DEFEAT", ownerEnd.get("state").get("teamResult").asString());
            assertEquals(0L, tank(ownerEnd.get("state"), 214L).get("shieldUntilTick").asLong());
            assertEquals(0L, tank(ownerEnd.get("state"), 215L).get("shieldUntilTick").asLong());
            assertTrue(ownerEnd.get("winnerUserId").isNull());
            assertNotEquals("TIME_LIMIT", ownerEnd.get("reason").asString());

            owner.send("/app/room/rematch", "{\"roomId\":\"" + roomId + "\"}");
            JsonNode waiting = owner.awaitType("room:rematched").get("payload");
            assertEquals("WAITING", waiting.get("status").asString());
            assertEquals("COOP", waiting.get("mode").asString());
            owner.send("/app/room/leave", "{\"roomId\":\"" + roomId + "\"}");
            owner.awaitType("room:left");
            teammate.send("/app/room/leave", "{\"roomId\":\"" + roomId + "\"}");
            teammate.awaitType("room:left");
        }
    }

    private TestPeer connect(Long userId) throws Exception {
        RecordingListener listener = new RecordingListener();
        WebSocket socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .newWebSocketBuilder().header("X-Test-User", userId.toString())
                .buildAsync(endpoint(), listener).get(5, TimeUnit.SECONDS);
        TestPeer peer = new TestPeer(socket, listener);
        peer.sendFrame("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0");
        assertEquals("CONNECTED", listener.nextFrame().getCommand());
        peer.sendFrame("SUBSCRIBE\nid:replies\ndestination:/user/queue/replies\n\n\0");
        peer.pingBarrier();
        return peer;
    }

    private URI endpoint() {
        URI uri = URI.create("ws://127.0.0.1:" + port + "/ws/tankgame");
        return uri;
    }

    private static String readyBody(String roomId) {
        String body = "{\"roomId\":\"" + roomId + "\",\"ready\":true}";
        return body;
    }

    private static String loadedBody(String roomId, String matchId) {
        String body = "{\"roomId\":\"" + roomId + "\",\"matchId\":\"" + matchId + "\"}";
        return body;
    }

    private static String inputBody(String roomId, String matchId, long seq, int direction) {
        String body = "{\"roomId\":\"" + roomId + "\",\"matchId\":\"" + matchId
                + "\",\"seq\":" + seq + ",\"direction\":" + direction + ",\"fireHeld\":false}";
        return body;
    }

    private static JsonNode listedRoom(JsonNode listed, String roomId) {
        for (JsonNode room : listed) {
            if (roomId.equals(room.get("roomId").asString())) return room;
        }
        return null;
    }

    private static JsonNode tank(JsonNode state, Long userId) {
        for (JsonNode tank : state.get("tanks")) {
            if (tank.get("userId").asLong() == userId.longValue()) {
                return tank;
            }
        }
        fail("快照中找不到指定玩家的坦克");
        return null;
    }

    private static GameMapResponse openMap() {
        List<List<Integer>> seed = new ArrayList<List<Integer>>();
        for (int row = 0; row < 8; row++) {
            List<Integer> cells = new ArrayList<Integer>();
            for (int column = 0; column < 8; column++) {
                boolean border = row == 0 || row == 7 || column == 0 || column == 7;
                if (border) {
                    cells.add(1);
                } else {
                    cells.add(0);
                }
            }
            seed.add(cells);
        }
        GameMapResponse map = new GameMapResponse(1L, "隔离测试地图", 24, seed);
        return map;
    }

    private static GameMapResponse corridorMap() {
        List<List<Integer>> seed = new ArrayList<List<Integer>>();
        for (int row = 0; row < 8; row++) {
            List<Integer> cells = new ArrayList<Integer>();
            for (int column = 0; column < 8; column++) {
                if (row == 3 && column >= 1 && column <= 6) cells.add(0);
                else cells.add(2);
            }
            seed.add(cells);
        }
        return new GameMapResponse(1L, "STOMP隔离战斗地图", 24, seed);
    }

    /** 测试专用身份来源，只在上面的独立测试上下文中使用。 */
    private static final class TestLoginAnswer implements Answer<BlogUser> {
        @Override
        public BlogUser answer(InvocationOnMock invocation) {
            HttpServletRequest request = invocation.getArgument(0);
            String userIdText = request.getHeader("X-Test-User");
            if (userIdText == null) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "测试请求未登录");
            }
            BlogUser user = new BlogUser();
            user.setId(Long.valueOf(userIdText));
            user.setUsername("test-user-" + userIdText);
            return user;
        }
    }

    @Getter
    @RequiredArgsConstructor
    private static final class StompFrame {
        private final String command;
        private final String body;
    }

    private static final class RecordingListener implements WebSocket.Listener {
        private final BlockingQueue<StompFrame> frames = new LinkedBlockingQueue<StompFrame>(256);
        private final StringBuilder incoming = new StringBuilder();
        private final CompletableFuture<Integer> closed = new CompletableFuture<Integer>();
        private final CompletableFuture<Throwable> transportError = new CompletableFuture<Throwable>();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            incoming.append(data);
            int terminator = incoming.indexOf("\0");
            while (terminator >= 0) {
                String raw = incoming.substring(0, terminator).replace("\r\n", "\n").stripLeading();
                incoming.delete(0, terminator + 1);
                if (!raw.isBlank()) {
                    int firstLine = raw.indexOf('\n');
                    int bodyStart = raw.indexOf("\n\n");
                    String command = firstLine < 0 ? raw : raw.substring(0, firstLine);
                    String body = bodyStart < 0 ? "" : raw.substring(bodyStart + 2);
                    boolean accepted = frames.offer(new StompFrame(command, body));
                    if (!accepted) throw new IllegalStateException("测试消息队列超限");
                }
                terminator = incoming.indexOf("\0");
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(statusCode);
            frames.offer(new StompFrame("CLOSED", Integer.toString(statusCode)));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            transportError.complete(error);
            frames.offer(new StompFrame("TRANSPORT_ERROR", error.getClass().getSimpleName()));
        }

        private StompFrame nextFrame() throws InterruptedException {
            StompFrame frame = frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "等待 STOMP 帧超时");
            return frame;
        }
    }

    @RequiredArgsConstructor
    private static final class TestPeer implements AutoCloseable {
        private final WebSocket socket;
        private final RecordingListener listener;
        private final JsonMapper mapper = new JsonMapper();
        private final List<JsonNode> pending = new ArrayList<JsonNode>();

        private void sendFrame(String frame) throws Exception {
            socket.sendText(frame, true).get(5, TimeUnit.SECONDS);
        }

        private void send(String destination, String body) throws Exception {
            String frame = "SEND\ndestination:" + destination + "\ncontent-type:application/json\n\n" + body + "\0";
            sendFrame(frame);
        }

        private void pingBarrier() throws Exception {
            send("/app/ping", "{\"text\":\"barrier\"}");
            JsonNode response = awaitType("pong");
            assertEquals("barrier", response.get("payload").asString());
        }

        private void awaitProtocolRejection() throws Exception {
            StompFrame frame = listener.frames.poll(5, TimeUnit.SECONDS);
            if (frame == null) {
                if (!socket.isInputClosed() && !socket.isOutputClosed()) {
                    pingBarrier();
                    fail("非法目标未产生ERROR或策略关闭，连接仍可继续处理合法指令");
                }
                fail("连接结束但未收到可核对的拒绝事件，不能把超时当成权限检查成功");
            }
            if ("ERROR".equals(frame.getCommand())) return;
            if ("CLOSED".equals(frame.getCommand())) {
                int code = Integer.parseInt(frame.getBody());
                assertTrue(code == 1002 || code == 1008,
                        "非法目标应由协议或策略关闭，实际关闭码：" + code);
                assertEquals(code, listener.closed.get(1, TimeUnit.SECONDS));
                return;
            }
            fail("非法目标没有被明确拒绝，收到：" + frame.getCommand() + " / " + frame.getBody());
        }

        private void assertNoPendingMessage(String type) throws Exception {
            for (JsonNode message : pending) {
                assertNotEquals(type, message.get("type").asString(), "目标账号收到伪造消息");
            }
            StompFrame frame;
            while ((frame = listener.frames.poll()) != null) {
                if ("MESSAGE".equals(frame.getCommand())) {
                    JsonNode message = mapper.readTree(frame.getBody());
                    assertNotEquals(type, message.get("type").asString(), "目标账号收到伪造消息");
                }
            }
        }

        private JsonNode awaitType(String type) throws Exception {
            JsonNode response = awaitMessage(type, null, null, null);
            return response;
        }

        private JsonNode awaitRoom(String status, Long ownerUserId, Integer memberCount) throws Exception {
            JsonNode response = awaitMessage("room:updated", status, ownerUserId, memberCount);
            return response;
        }

        private JsonNode awaitRoomVersion(String status, Long ownerUserId, Integer memberCount,
                                          long minimumVersion) throws Exception {
            while (true) {
                JsonNode response = awaitRoom(status, ownerUserId, memberCount);
                if (response.get("payload").get("version").asLong() >= minimumVersion) return response;
            }
        }

        private JsonNode awaitConnectedRoom(String status, Long ownerUserId, Integer memberCount,
                                           int connectedCount, long minimumVersion) throws Exception {
            while (true) {
                JsonNode response = awaitRoomVersion(status, ownerUserId, memberCount, minimumVersion);
                if (response.get("payload").get("connectedUserIds").size() == connectedCount) return response;
            }
        }

        private JsonNode awaitMessage(String type, String status, Long ownerUserId, Integer memberCount) throws Exception {
            for (int index = 0; index < pending.size(); index++) {
                JsonNode message = pending.get(index);
                if (matches(message, type, status, ownerUserId, memberCount)) {
                    pending.remove(index);
                    return message;
                }
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                StompFrame frame = listener.nextFrame();
                if ("ERROR".equals(frame.getCommand())) {
                    fail("服务端返回 STOMP ERROR：" + frame.getBody());
                }
                if (!"MESSAGE".equals(frame.getCommand())) continue;
                JsonNode message = mapper.readTree(frame.getBody());
                if ("error".equals(message.get("type").asString()) && !"error".equals(type)) {
                    fail("服务端返回业务错误：" + message.get("payload"));
                }
                if (matches(message, type, status, ownerUserId, memberCount)) return message;
                pending.add(message);
            }
            fail("等待消息超时，type=" + type);
            return null;
        }

        private boolean matches(JsonNode message, String type, String status, Long ownerUserId, Integer memberCount) {
            if (!type.equals(message.get("type").asString())) return false;
            JsonNode payload = message.get("payload");
            if (status != null && !status.equals(payload.get("status").asString())) return false;
            if (ownerUserId != null && payload.get("ownerUserId").asLong() != ownerUserId.longValue()) return false;
            if (memberCount != null && payload.get("memberUserIds").size() != memberCount.intValue()) return false;
            return true;
        }

        @Override
        public void close() {
            socket.abort();
        }
    }
}
