package com.syf.blog.test;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用独立的测试应用，不连接开发数据库。 */
@SpringBootTest(
        classes = MyWsIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "demo.websocket.enabled=false",
                "spring.sql.init.mode=never",
                "spring.jpa.hibernate.ddl-auto=validate"
        })
class MyWsDisabledIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void disabledDemoDoesNotCreateEndpointConfigurationOrPushTask() {
        assertTrue(applicationContext.getBeansOfType(MyWsConfig.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(MyWsPushTask.class).isEmpty());
        // 处理器仍可作为普通 Bean 存在，但没有配置类为它注册连接地址。
        assertFalse(applicationContext.getBeansOfType(MyWsHandler.class).isEmpty());
    }

    @Test
    void disabledDemoEndpointReturnsNotFound() throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/myWs");
        HttpRequest request = HttpRequest.newBuilder(endpoint).GET().build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }
}
