package com.syf.blog.controller;

import com.syf.blog.domain.BlogUser;
import com.syf.blog.repository.BlogUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * 用户认证接口，提供注册、登录、查询当前用户和退出登录功能。
 *
 * <p>基础路径：{@code /api/auth}。登录身份保存在后端 Session 的 {@code loginUserId} 中。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class UserController {
    private final BlogUserRepository users;
    // 使用 BCrypt 编码注册密码，并校验登录密码。
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 创建用户控制器，由 Spring 注入用户仓库。
     *
     * @param users 用户数据仓库，用于查询和保存用户
     */
    public UserController(BlogUserRepository users) {
        this.users= users;
    }

    /**
     * 注册账号的 JSON 请求体。
     *
     * @param username 用户名，不能为空或全为空白，注册时去掉两端空白
     * @param password 明文密码，不能为空或全为空白，至少 8 个字符，保存前使用 BCrypt 编码
     */

    public record  RegisterRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 8) String password
    ){}

    /**
     * 登录账号的 JSON 请求体。
     *
     * @param username 用户名，不能为空或全为空白，查询时去掉两端空白
     * @param password 明文密码，不能为空或全为空白，用于与数据库中的密码哈希校验
     */
    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ){}

    /**
     * 注册一个默认角色为 {@code USER}、启用状态为 {@code true} 的账号。
     *
     * <p>请求：{@code POST /api/auth/register}，请求体格式为 JSON，允许匿名访问。
     * 注册成功仅创建账号，不建立登录会话。</p>
     *
     * @param request JSON 请求体，包含用户名和至少 8 个字符的密码
     * @return 成功返回 201 和用户的 {@code id}、{@code username}；
     *         参数校验失败返回 400；用户名已存在返回 409
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(
            @Valid @RequestBody RegisterRequest request
    ){
        String username = request.username().trim();
        // 用户名重复时返回 HTTP 409，表示与已有数据冲突。
        if(users.existsByUsername(username)){
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "用户名已经存在"));
        }

        BlogUser user = users.save(
                new BlogUser(username, encoder.encode(request.password()))
        );

        return ResponseEntity.status(HttpStatus.CREATED).
                body(Map.of("id",user.getId(),"username",user.getUsername()));

    }

    /**
     * 校验用户名和密码，为启用的账号建立登录会话。
     *
     * <p>请求：{@code POST /api/auth/login}，请求体格式为 JSON，允许匿名访问。
     * 成功后更换 Session ID，并在 Session 中保存用户 ID。</p>
     *
     * @param request JSON 请求体，包含用户名和密码
     * @param httpRequest 当前 HTTP 请求，用于创建或读取 Session 并更换会话 ID
     * @return 成功返回 200 和 {@code id}、{@code username}、{@code role}；
     *         参数校验失败返回 400；用户名或密码错误返回 401；账号停用返回 403
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        Optional<BlogUser> result = users.findByUsername(request.username().trim());

        if (result.isEmpty()
                || !encoder.matches(request.password(), result.get().getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "用户名或密码错误"));
        }

        BlogUser user = result.get();

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "账号已停用"));
        }
        // 获取当前会话，没有会话就创建一个
        HttpSession session = httpRequest.getSession();

        // 登录成功后更换会话编号
        httpRequest.changeSessionId();
        // 在后端会话中保存登录用户的 ID
        session.setAttribute("loginUserId",user.getId());


        return ResponseEntity.ok(
                Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "role", user.getRoleCode()
                )
        );
    }

    /**
     * 根据已有登录会话，查询当前用户的信息和最新角色。
     *
     * <p>请求：{@code GET /api/auth/me}，需要有效且启用的登录账号。
     * 用户不存在或账号停用时使 Session 失效；此接口不会创建新 Session。</p>
     *
     * @param httpRequest 当前 HTTP 请求，用于读取 Session 中的 {@code loginUserId}
     * @return 成功返回 200 和 {@code id}、{@code username}、{@code role}；
     *         未登录、用户不存在或账号停用时返回 401
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest httpRequest){

        // 只读取已有会话，没有就返回 null。
        HttpSession session = httpRequest.getSession(false);

        if(session == null|| session.getAttribute("loginUserId") == null){
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message","请先登录"));
        }

        Long userId = (Long) session.getAttribute("loginUserId");

        Optional<BlogUser> result = users.findById(userId);


        if(result.isEmpty()){
            session.invalidate();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message","登录用户不存在，请重新登录"));
        }
        BlogUser user = result.get();

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            session.invalidate();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "账号已停用"));
        }

        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role",user.getRoleCode()
        ));
    }

    /**
     * 退出登录，使当前已有的 Session 失效。
     *
     * <p>请求：{@code POST /api/auth/logout}。未登录时调用也能成功，不创建新 Session。</p>
     *
     * @param httpRequest 当前 HTTP 请求，用于查找并使已有 Session 失效
     * @return 204，响应体为空
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);

        if (session != null) {
            session.invalidate();
        }

        return ResponseEntity.noContent().build();
    }
}
