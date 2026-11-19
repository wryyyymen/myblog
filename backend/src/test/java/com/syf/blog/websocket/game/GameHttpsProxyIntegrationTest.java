package com.syf.blog.websocket.game;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复用隔离的游戏上下文和内存身份，验证 Nginx 在本机终止 HTTPS 后的握手。
 * 数据源自动配置已由复用的 fixture 排除，不访问开发或生产数据库。
 */
@SpringBootTest(
        classes = GameMovementIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "server.forward-headers-strategy=native",
                "spring.sql.init.mode=never",
                "spring.jpa.hibernate.ddl-auto=validate",
                "debug=false"
        })
class GameHttpsProxyIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void loopbackHttpsProxyAllowsTheOriginalSiteOrigin() throws Exception {
        int status = handshakeStatus("https://stefanblog.top", true);

        assertEquals(101, status);
    }

    @Test
    void loopbackHttpsProxyStillRejectsAnotherOrigin() throws Exception {
        int status = handshakeStatus("https://another-site.example", true);

        assertEquals(403, status);
    }

    @Test
    void httpsOriginWithoutTheForwardedProtocolIsRejected() throws Exception {
        int status = handshakeStatus("https://stefanblog.top", false);

        assertEquals(403, status);
    }

    private int handshakeStatus(String origin, boolean forwardedHttps) throws Exception {
        // 连接到真实随机端口，模拟本机 Nginx 发给 Tomcat 的 HTTP Upgrade 请求。
        // 手写请求可以保留外部 Host，不受 Java HttpClient 对 Host 请求头的限制。
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.US_ASCII));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));

            StringBuilder request = new StringBuilder();
            request.append("GET /ws/tankgame HTTP/1.1\r\n");
            request.append("Host: stefanblog.top\r\n");
            request.append("Upgrade: websocket\r\n");
            request.append("Connection: Upgrade\r\n");
            request.append("Sec-WebSocket-Version: 13\r\n");
            request.append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
            request.append("Sec-WebSocket-Protocol: v12.stomp\r\n");
            request.append("Origin: ").append(origin).append("\r\n");
            // 此身份头只由测试 fixture 读取，生产身份服务不接受这个头。
            request.append("X-Test-User: 301\r\n");
            if (forwardedHttps) {
                request.append("X-Forwarded-Proto: https\r\n");
            }
            request.append("\r\n");

            writer.write(request.toString());
            writer.flush();

            String statusLine = reader.readLine();
            assertNotNull(statusLine, "后端应返回 HTTP 握手响应");
            assertTrue(statusLine.startsWith("HTTP/1.1 "), statusLine);

            String[] statusParts = statusLine.split(" ", 3);
            int status = Integer.parseInt(statusParts[1]);
            return status;
        }
    }
}
