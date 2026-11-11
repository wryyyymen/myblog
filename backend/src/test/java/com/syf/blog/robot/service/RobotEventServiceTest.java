package com.syf.blog.robot.service;

import com.syf.blog.robot.dto.RobotRecognitionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotEventServiceTest {
    @Test
    void streamExpiresAndIsActivelyCompleted() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(100, 0, false);
        try (RobotEventService events = new RobotEventService(100, () -> emitter)) {
            assertEquals(100L, events.subscribe(null).getTimeout());
            assertTrue(emitter.completed.await(2, TimeUnit.SECONDS));

            events.publish("过期后", null, null);
            assertTrue(emitter.receivedSequences.isEmpty());
        }
    }

    @Test
    void slowSubscriberCannotBlockPublishingAndReplayStaysOrdered() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(5_000, 2, true);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (RobotEventService events = new RobotEventService(5_000, () -> emitter)) {
            events.subscribe(null);
            Future<Long> first = caller.submit(() -> events.publish("第一条", 1, 90).sequence());
            assertEquals(1L, first.get(1, TimeUnit.SECONDS));
            assertTrue(emitter.firstRecognitionStarted.await(1, TimeUnit.SECONDS));

            Future<Long> second = caller.submit(() -> events.publish("第二条", 2, 90).sequence());
            assertEquals(2L, second.get(1, TimeUnit.SECONDS));
            emitter.releaseFirstRecognition.countDown();

            assertTrue(emitter.expectedEvents.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1L, 2L), emitter.receivedSequences);
        } finally {
            emitter.releaseFirstRecognition.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void replaysOnlyEventsAfterLastEventIdBeforeNewLiveEvents() throws Exception {
        RecordingEmitter initial = new RecordingEmitter(5_000, 1, false);
        RecordingEmitter replay = new RecordingEmitter(5_000, 3, false);
        AtomicInteger connections = new AtomicInteger();
        try (RobotEventService events = new RobotEventService(5_000,
                () -> connections.getAndIncrement() == 0 ? initial : replay)) {
            events.subscribe(null);
            events.publish("已收到", null, null);
            assertTrue(initial.expectedEvents.await(2, TimeUnit.SECONDS));
            String lastEventId = initial.receivedEventIds.get(0);
            assertTrue(lastEventId.matches("[0-9a-f-]{36}:1"));
            events.publish("待补发一", null, null);
            events.publish("待补发二", null, null);
            events.subscribe(lastEventId);
            events.publish("新结果", null, null);

            assertTrue(replay.expectedEvents.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(2L, 3L, 4L), replay.receivedSequences);
            assertEquals(List.of(lastEventId.substring(0, lastEventId.length() - 1) + "2",
                    lastEventId.substring(0, lastEventId.length() - 1) + "3",
                    lastEventId.substring(0, lastEventId.length() - 1) + "4"), replay.receivedEventIds);
        }
    }

    @Test
    void oldProcessEventIdCannotSkipEventsAfterRestart() throws Exception {
        RecordingEmitter beforeRestart = new RecordingEmitter(5_000, 1, false);
        String oldEventId;
        try (RobotEventService oldProcess = new RobotEventService(5_000, () -> beforeRestart)) {
            oldProcess.subscribe(null);
            oldProcess.publish("旧结果", null, null);
            assertTrue(beforeRestart.expectedEvents.await(2, TimeUnit.SECONDS));
            oldEventId = beforeRestart.receivedEventIds.get(0);
        }

        RecordingEmitter afterRestart = new RecordingEmitter(5_000, 2, false);
        try (RobotEventService newProcess = new RobotEventService(5_000, () -> afterRestart)) {
            newProcess.publish("新结果一", null, null);
            newProcess.publish("新结果二", null, null);
            newProcess.subscribe(oldEventId);
            assertTrue(afterRestart.expectedEvents.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1L, 2L), afterRestart.receivedSequences);
            assertNotEquals(oldEventId, afterRestart.receivedEventIds.get(0));
        }
    }

    @Test
    void fullSubscriberQueueClosesTheSlowStream() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(5_000, 0, true);
        try (RobotEventService events = new RobotEventService(5_000, () -> emitter)) {
            events.subscribe(null);
            events.publish("阻塞中的结果", null, null);
            assertTrue(emitter.firstRecognitionStarted.await(1, TimeUnit.SECONDS));

            for (int index = 0; index < 70; index++) {
                events.publish("后续结果" + index, null, null);
            }
            assertTrue(emitter.completed.await(2, TimeUnit.SECONDS));
            assertEquals(71L, events.latest().orElseThrow().sequence());
        } finally {
            emitter.releaseFirstRecognition.countDown();
        }
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final List<Long> receivedSequences = Collections.synchronizedList(new ArrayList<>());
        private final List<String> receivedEventIds = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch expectedEvents;
        private final CountDownLatch firstRecognitionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstRecognition = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final boolean blockFirstRecognition;

        private RecordingEmitter(long timeoutMillis, int expectedEvents, boolean blockFirstRecognition) {
            super(timeoutMillis);
            this.expectedEvents = new CountDownLatch(expectedEvents);
            this.blockFirstRecognition = blockFirstRecognition;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            String eventId = null;
            for (var item : builder.build()) {
                if (item.getData() instanceof String wireText) {
                    for (String line : wireText.split("\\n")) {
                        if (line.startsWith("id:")) {
                            eventId = line.substring(3);
                        }
                    }
                }
                if (item.getData() instanceof RobotRecognitionResponse event) {
                    if (event.sequence() == 1) {
                        firstRecognitionStarted.countDown();
                        if (blockFirstRecognition) {
                            try {
                                if (!releaseFirstRecognition.await(5, TimeUnit.SECONDS)) {
                                    throw new IOException("Timed out waiting for test release");
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IOException(exception);
                            }
                        }
                    }
                    receivedSequences.add(event.sequence());
                    receivedEventIds.add(eventId);
                    expectedEvents.countDown();
                }
            }
        }

        @Override
        public void complete() {
            completed.countDown();
            super.complete();
        }
    }
}
