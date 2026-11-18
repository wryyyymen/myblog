package com.syf.blog.robot.transport;

import com.syf.blog.robot.recognition.NativeRecognizerClient;
import com.syf.blog.robot.recognition.RobotRecognitionXmlParser;
import com.syf.blog.robot.service.RobotEventService;
import com.syf.blog.robot.service.RobotResultService;
import com.syf.blog.robot.service.RobotVoiceService;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotTcpServerTest {
    private static final String VALID_XML = "<nlp><rawtext>open</rawtext><result><confidence>95</confidence>"
            + "<object><cmd id=\"2\">open</cmd></object></result></nlp>";
    private static final String FALLBACK_XML = "<nlp><rawtext></rawtext><result>"
            + "<confidence>0</confidence></result></nlp>";

    @Test
    void twoAudioRoundsKeepOneBoardConnectionAndPublishCompleteResults() throws Exception {
        byte[] xml = VALID_XML.getBytes(StandardCharsets.UTF_8);
        byte[] first = frame((byte) 7);
        byte[] second = frame((byte) 9);
        try (ServerSocket worker = workerSocket(); RobotEventService events = new RobotEventService()) {
            var executor = Executors.newSingleThreadExecutor();
            try {
            Future<List<byte[]>> received = executor.submit(() -> {
                byte[][] frames = new byte[2][];
                for (int i = 0; i < frames.length; i++) {
                    try (Socket connection = worker.accept()) {
                        connection.setSoTimeout(3_000);
                        frames[i] = new byte[NativeRecognizerClient.PCM_FRAME_BYTES];
                        new DataInputStream(connection.getInputStream()).readFully(frames[i]);
                        DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                        output.writeInt(xml.length);
                        output.write(xml);
                        output.flush();
                    }
                }
                return Arrays.asList(frames);
            });
            RobotTcpServer server = server(worker.getLocalPort(), events, "127.0.0.1", 100);
            try {
                server.start();
                try (Socket board = boardSocket(server.boundPort())) {
                    board.getOutputStream().write(first, 0, 1_000);
                    board.getOutputStream().write(first, 1_000, first.length - 1_000);
                    board.getOutputStream().flush();
                    assertArrayEquals(xml, board.getInputStream().readNBytes(xml.length));
                    Thread.sleep(250); // Longer than the test frame timeout; idle must stay connected.
                    board.getOutputStream().write(second);
                    board.getOutputStream().flush();
                    assertArrayEquals(xml, board.getInputStream().readNBytes(xml.length));
                }
                assertEquals(List.of(7, 9), received.get(5, TimeUnit.SECONDS).stream()
                        .map(bytes -> Byte.toUnsignedInt(bytes[0])).toList());
                assertEquals(2, events.latest().orElseThrow().commandId());
                assertEquals("open", events.latest().orElseThrow().text());
            } finally {
                server.stop();
            }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void incompleteFrameDisconnectDoesNotPreventAnotherBoardConnection() throws Exception {
        byte[] xml = VALID_XML.getBytes(StandardCharsets.UTF_8);
        try (ServerSocket worker = workerSocket(); RobotEventService events = new RobotEventService()) {
            var executor = Executors.newSingleThreadExecutor();
            try {
            Future<byte[]> received = executor.submit(() -> {
                try (Socket connection = worker.accept()) {
                    connection.setSoTimeout(3_000);
                    byte[] pcm = new byte[NativeRecognizerClient.PCM_FRAME_BYTES];
                    new DataInputStream(connection.getInputStream()).readFully(pcm);
                    DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                    output.writeInt(xml.length);
                    output.write(xml);
                    output.flush();
                    return pcm;
                }
            });
            RobotTcpServer server = server(worker.getLocalPort(), events, "127.0.0.1");
            try {
                server.start();
                try (Socket board = boardSocket(server.boundPort())) {
                    board.getOutputStream().write(new byte[500]);
                }
                assertTrue(events.latest().isEmpty());
                try (Socket replacement = boardSocket(server.boundPort())) {
                    replacement.getOutputStream().write(frame((byte) 3));
                    replacement.getOutputStream().flush();
                    assertArrayEquals(xml, replacement.getInputStream().readNBytes(xml.length));
                }
                assertEquals(3, Byte.toUnsignedInt(received.get(5, TimeUnit.SECONDS)[0]));
                assertTrue(events.latest().isPresent());
            } finally {
                server.stop();
            }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void unavailableWorkerReturnsLowConfidenceXmlWithoutPublishing() throws Exception {
        int unavailablePort;
        try (ServerSocket temporary = workerSocket()) {
            unavailablePort = temporary.getLocalPort();
        }
        try (RobotEventService events = new RobotEventService()) {
            RobotTcpServer server = server(unavailablePort, events, "127.0.0.1");
            try {
                server.start();
                try (Socket board = boardSocket(server.boundPort())) {
                    board.getOutputStream().write(frame((byte) 1));
                    board.getOutputStream().flush();
                    byte[] expected = FALLBACK_XML.getBytes(StandardCharsets.UTF_8);
                    assertArrayEquals(expected, board.getInputStream().readNBytes(expected.length));
                }
                assertTrue(events.latest().isEmpty());
            } finally {
                server.stop();
            }
        }
    }

    @Test
    void listenerRejectsUnapprovedAddressAndRequiresConfiguration() throws Exception {
        try (RobotEventService events = new RobotEventService()) {
            RobotVoiceService voice = new RobotVoiceService(
                    new NativeRecognizerClient("127.0.0.1", 54322),
                    new RobotResultService(new RobotRecognitionXmlParser(), events));
            assertThrows(IllegalArgumentException.class,
                    () -> new RobotTcpServer(voice, "127.0.0.1", 0, ""));
            RobotTcpServer server = new RobotTcpServer(voice, "127.0.0.1", 0, "127.0.0.2");
            try {
                server.start();
                try (Socket board = boardSocket(server.boundPort())) {
                    assertEquals(-1, board.getInputStream().read());
                }
                assertFalse(events.latest().isPresent());
            } finally {
                server.stop();
            }
        }
    }

    @Test
    void bindingFailureIsReportedAtStartup() throws Exception {
        try (RobotEventService events = new RobotEventService()) {
            RobotVoiceService voice = new RobotVoiceService(
                    new NativeRecognizerClient("127.0.0.1", 54322),
                    new RobotResultService(new RobotRecognitionXmlParser(), events));
            RobotTcpServer first = new RobotTcpServer(voice, "127.0.0.1", 0, "127.0.0.1");
            try {
                first.start();
                RobotTcpServer second = new RobotTcpServer(voice, "127.0.0.1", first.boundPort(), "127.0.0.1");
                assertThrows(IllegalStateException.class, second::start);
                second.stop();
            } finally {
                first.stop();
            }
        }
    }

    private static RobotTcpServer server(int workerPort, RobotEventService events, String allowedAddress) {
        return server(workerPort, events, allowedAddress, 30_000);
    }

    private static RobotTcpServer server(int workerPort, RobotEventService events,
                                         String allowedAddress, int frameReadTimeoutMs) {
        RobotResultService results = new RobotResultService(new RobotRecognitionXmlParser(), events);
        RobotVoiceService voice = new RobotVoiceService(new NativeRecognizerClient("127.0.0.1", workerPort), results);
        return new RobotTcpServer(voice, "127.0.0.1", 0, allowedAddress, frameReadTimeoutMs);
    }

    private static Socket boardSocket(int port) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3_000);
        return socket;
    }

    private static ServerSocket workerSocket() throws Exception {
        ServerSocket worker = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        worker.setSoTimeout(3_000);
        return worker;
    }

    private static byte[] frame(byte value) {
        byte[] pcm = new byte[NativeRecognizerClient.PCM_FRAME_BYTES];
        Arrays.fill(pcm, value);
        return pcm;
    }
}
