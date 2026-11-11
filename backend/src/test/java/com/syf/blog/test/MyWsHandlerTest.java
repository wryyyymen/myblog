package com.syf.blog.test;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyWsHandlerTest {

    @Test
    void failedSendRemovesOnlyThatConnectionAndOtherClientsKeepReceiving() throws Exception {
        MyWsHandler handler = new MyWsHandler();
        WebSocketSession failed = openSession("failed");
        WebSocketSession healthy = openSession("healthy");
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            if (message.getPayload().equals("push: first")) {
                throw new IOException("client disconnected while sending");
            }
            return null;
        }).when(failed).sendMessage(any());

        handler.afterConnectionEstablished(failed);
        handler.afterConnectionEstablished(healthy);
        assertEquals(2, handler.getConnectionCount());

        handler.broadcast("push: first");
        assertEquals(1, handler.getConnectionCount());
        verify(failed).close(CloseStatus.SERVER_ERROR);
        verify(healthy).sendMessage(argThat(message ->
                message instanceof TextMessage text && text.getPayload().equals("push: first")));

        handler.broadcast("push: second");
        verify(healthy).sendMessage(argThat(message ->
                message instanceof TextMessage text && text.getPayload().equals("push: second")));
    }

    @Test
    void echoAndBroadcastDoNotSendConcurrentlyOnOneConnection() throws Exception {
        MyWsHandler handler = new MyWsHandler();
        WebSocketSession session = openSession("one-client");
        CountDownLatch broadcastEntered = new CountDownLatch(1);
        CountDownLatch releaseBroadcast = new CountDownLatch(1);
        AtomicInteger sending = new AtomicInteger();
        AtomicInteger peakConcurrentSends = new AtomicInteger();
        List<String> delivered = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            int active = sending.incrementAndGet();
            peakConcurrentSends.accumulateAndGet(active, Math::max);
            try {
                delivered.add(message.getPayload());
                if (message.getPayload().equals("push: now")) {
                    broadcastEntered.countDown();
                    if (!releaseBroadcast.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test timed out waiting to release send");
                    }
                }
            } finally {
                sending.decrementAndGet();
            }
            return null;
        }).when(session).sendMessage(any());

        handler.afterConnectionEstablished(session);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> broadcast = workers.submit(() -> handler.broadcast("push: now"));
            assertTrue(broadcastEntered.await(5, TimeUnit.SECONDS));
            Future<?> echo = workers.submit(() -> {
                handler.handleMessage(session, new TextMessage("hello"));
                return null;
            });
            echo.get(5, TimeUnit.SECONDS);
            releaseBroadcast.countDown();
            broadcast.get(5, TimeUnit.SECONDS);

            assertEquals(1, peakConcurrentSends.get());
            assertTrue(delivered.contains("push: now"));
            assertTrue(delivered.contains("echo: hello"));
        } finally {
            releaseBroadcast.countDown();
            workers.shutdownNow();
        }
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
