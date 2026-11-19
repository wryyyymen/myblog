package com.syf.blog.websocket.game;

import com.syf.blog.auth.CurrentUserService;
import com.syf.blog.auth.GameHandshakeHandler;
import com.syf.blog.auth.GameHandshakeInterceptor;
import com.syf.blog.config.GameLoopConfig;
import com.syf.blog.config.GameWebSocketConfig;
import com.syf.blog.controller.GameMessageController;
import com.syf.blog.controller.GamePlayController;
import com.syf.blog.domain.BlogUser;
import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.dto.user.UserResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.exception.GlobalExceptionHandler;
import com.syf.blog.game.engine.GameArenaMapFactory;
import com.syf.blog.service.GameMapService;
import com.syf.blog.service.game.GameMatchService;
import com.syf.blog.service.game.GameRoomService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 仅用于本地浏览器联调的隔离服务，不是 JUnit 测试，不会由 surefire 自动启动。
 * 位于 src/test，生产构建不包含此类和测试登录入口，也不访问开发数据库。
 */
public class GameBrowserTestServer {

    public static void main(String[] args) {
        Path frontendDirectory;
        if (args.length > 0) {
            frontendDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        } else {
            frontendDirectory = Path.of("..", "frontend", "dist").toAbsolutePath().normalize();
        }
        if (!Files.isRegularFile(frontendDirectory.resolve("index.html"))) {
            throw new IllegalArgumentException("请先构建前端，并将 frontend/dist 的绝对目录作为第一个参数");
        }

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("server.port", "18082");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.servlet.session.cookie.name", "GAME_BROWSER_TEST");
        properties.put("spring.web.resources.static-locations", frontendDirectory.toUri().toString());
        properties.put("spring.web.resources.cache.period", "0");
        properties.put("spring.sql.init.mode", "never");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("debug", "false");
        properties.put("logging.level.root", "WARN");
        properties.put("spring.main.banner-mode", "off");

        SpringApplication application = new SpringApplication(TestApplication.class);
        application.setDefaultProperties(properties);
        // 命令行参数优先于本地配置，确保测试服务不会占用生产开发端口。
        application.run("--server.address=127.0.0.1", "--server.port=18082", "--debug=false",
                "--server.servlet.session.cookie.name=GAME_BROWSER_TEST",
                "--spring.sql.init.mode=never", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.web.resources.static-locations=" + frontendDirectory.toUri());
        System.out.println("[GameBrowserTestServer] http://127.0.0.1:18082/game，隔离测试身份与内置地图，无数据库访问");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({GameWebSocketConfig.class, GameLoopConfig.class, GameHandshakeInterceptor.class,
            GameHandshakeHandler.class, GameRoomService.class, GameMatchService.class,
            GameRoomNotifier.class, GamePresenceListener.class, GameMessageController.class,
            GamePlayController.class, GlobalExceptionHandler.class, TestHttpController.class,
            TestSpaController.class})
    static class TestApplication {

        @Bean
        CurrentUserService currentUserService() {
            return new TestSessionCurrentUserService();
        }

        @Bean
        GameMapService gameMapService() {
            return new TestMapService();
        }
    }

    /** 只在本测试应用内读取测试 Session，构造时不提供或使用 Repository。 */
    static class TestSessionCurrentUserService extends CurrentUserService {
        TestSessionCurrentUserService() {
            super(null);
        }

        @Override
        public BlogUser requireLogin(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            if (session == null || !(session.getAttribute("loginUserId") instanceof Long)) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录测试账号");
            }
            Long userId = (Long) session.getAttribute("loginUserId");
            return testUser(userId);
        }

        @Override
        public BlogUser requireProfileUser(HttpServletRequest request) {
            return requireLogin(request);
        }
    }

    /** 地图也只来自内存，不读取数据库。 */
    static class TestMapService extends GameMapService {
        TestMapService() {
            super(null);
        }

        @Override
        public GameMapResponse getById(Long id) {
            GameArenaMapFactory factory = new GameArenaMapFactory();
            return factory.createArena();
        }
    }

    @RestController
    @RequiredArgsConstructor
    static class TestHttpController {
        private final CurrentUserService users;

        @GetMapping("/__test/login/{id}")
        UserResponse loginById(@PathVariable Long id, HttpServletRequest request) {
            if (id != 101L && id != 102L && id != 103L) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "测试账号仅支持101、102、103");
            }
            users.startSession(request, id);
            return UserResponse.from(users.requireLogin(request));
        }

        @PostMapping("/api/auth/login")
        UserResponse login(@RequestBody TestLoginRequest body, HttpServletRequest request) {
            String username = body.getUsername();
            Long userId;
            if ("player1".equals(username) || "test101".equals(username)) userId = 101L;
            else if ("player2".equals(username) || "test102".equals(username)) userId = 102L;
            else if ("player3".equals(username) || "test103".equals(username)) userId = 103L;
            else throw new BusinessException(HttpStatus.BAD_REQUEST, "测试用户名为player1、player2、player3");
            return loginById(userId, request);
        }

        @GetMapping("/api/auth/me")
        UserResponse me(HttpServletRequest request) {
            return UserResponse.from(users.requireProfileUser(request));
        }

        @PostMapping("/api/auth/logout")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void logout(HttpServletRequest request) {
            users.logout(request);
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    static class TestLoginRequest {
        private String username;
        private String password;
    }

    @Controller
    static class TestSpaController {
        @GetMapping({"/", "/game", "/game/**", "/login", "/register", "/about"})
        String spa() {
            return "forward:/index.html";
        }
    }

    private static BlogUser testUser(Long userId) {
        BlogUser user = new BlogUser();
        user.setId(userId);
        user.setUsername("player" + (userId - 100L));
        user.setRoleCode("USER");
        user.setEnabled(true);
        return user;
    }
}
