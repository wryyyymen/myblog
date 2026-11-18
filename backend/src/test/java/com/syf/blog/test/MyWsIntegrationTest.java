package com.syf.blog.test;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MyWsIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "demo.websocket.push-delay-ms=600000",
                "spring.sql.init.mode=never",
                "spring.jpa.hibernate.ddl-auto=validate"
        })
class MyWsIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({MyWsConfig.class, MyWsHandler.class, MyWsInterceptor.class, MyWsPushTask.class})
    static class TestApplication {
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MyWsHandler handler;

    @Autowired
    private MyWsPushTask pushTask;

    @Test
    void connectionReceivesWelcomeEchoAndPushThenIsRemovedOnClose() throws Exception {
        RecordingListener listener = new RecordingListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(endpoint(), listener)
                .get(10, TimeUnit.SECONDS);

        try {
            String welcome = listener.nextMessage();
            assertTrue(welcome.matches("welcome: clientId=\\d+"), welcome);
            awaitConnectionCount(1);

            socket.sendText("hello websocket", true).get(10, TimeUnit.SECONDS);
            assertEquals("echo: hello websocket", listener.nextMessage());

            pushTask.sendMsg();
            String push = listener.nextMessage();
            assertTrue(push.startsWith("push: server time="), push);
            Instant.parse(push.substring("push: server time=".length()));

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
            assertEquals(WebSocket.NORMAL_CLOSURE, listener.closed.get(10, TimeUnit.SECONDS));
            awaitConnectionCount(0);
        } finally {
            if (!socket.isOutputClosed()) {
                socket.abort();
            }
        }
    }

    @Test
    void crossOriginHandshakeIsRejected() {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> HttpClient.newHttpClient().newWebSocketBuilder()
                        .header("Origin", "https://not-the-blog.example")
                        .buildAsync(endpoint(), new RecordingListener())
                        .get(10, TimeUnit.SECONDS));

        WebSocketHandshakeException handshake = assertInstanceOf(
                WebSocketHandshakeException.class, failure.getCause());
        assertEquals(403, handshake.getResponse().statusCode());
    }

    @Test
    void browserExampleIsServedFromTheSameHostAsTheEndpoint() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ws-client.html"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("new WebSocket(endpoint)"));
        assertTrue(response.body().contains("${location.host}/myWs"));
    }

    private URI endpoint() {
        return URI.create("ws://127.0.0.1:" + port + "/myWs");
    }

    private void awaitConnectionCount(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handler.getConnectionCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, handler.getConnectionCount());
    }

    private static final class RecordingListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                messages.add(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }

        private String nextMessage() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "Timed out waiting for a WebSocket text message");
            return message;
        }
    }
}
