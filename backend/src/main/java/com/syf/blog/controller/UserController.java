package com.syf.blog.controller;

import com.syf.blog.auth.CurrentUserService;
import com.syf.blog.dto.user.LoginRequest;
import com.syf.blog.dto.user.RegisterRequest;
import com.syf.blog.dto.user.RegisterResponse;
import com.syf.blog.dto.user.UserResponse;
import com.syf.blog.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 用户认证接口，提供注册、登录、查询当前用户和退出登录功能。
 *
 * <p>基础路径：{@code /api/auth}。登录身份保存在后端 Session 的 {@code loginUserId} 中。</p>
 */
@Tag(name = "用户认证", description = "注册、Session 登录、当前用户和退出登录")
@RestController
@RequestMapping("/api/auth")
public class UserController {
    private final UserService userService;
    private final CurrentUserService currentUserService;

    /**
     * @param userService 用户注册及凭据校验服务
     * @param currentUserService Session 身份及会话管理组件
     */
    public UserController(UserService userService, CurrentUserService currentUserService) {
        this.userService = userService;
        this.currentUserService = currentUserService;
    }

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
    @Operation(
            summary = "注册账号",
            description = "允许匿名访问。创建启用的 USER 账号，密码使用 BCrypt 保存；注册成功不会自动登录。",
            responses = {
                    @ApiResponse(responseCode = "201", description = "注册成功，返回 id 和 username"),
                    @ApiResponse(responseCode = "400", description = "请求参数不合法"),
                    @ApiResponse(responseCode = "409", description = "用户名已存在")
            })
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
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
    @Operation(
            summary = "用户登录",
            description = "校验密码及账号状态，建立 Session 并更换 Session ID。后续请求携带登录响应设置的会话 Cookie。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "登录成功，返回 id、username 和 role"),
                    @ApiResponse(responseCode = "400", description = "请求参数不合法"),
                    @ApiResponse(responseCode = "401", description = "用户名或密码错误"),
                    @ApiResponse(responseCode = "403", description = "账号已停用")
            })
    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        UserResponse user = userService.authenticate(request);
        currentUserService.startSession(httpRequest, user.id());
        return ResponseEntity.ok(user);
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
    @Operation(
            summary = "查询当前登录用户",
            description = "根据 Session 查询数据库中的最新角色及账号状态。用户不存在或账号停用时使会话失效。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "返回 id、username 和 role"),
                    @ApiResponse(responseCode = "401", description = "未登录、用户不存在或账号已停用")
            })
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                UserResponse.from(currentUserService.requireProfileUser(httpRequest)));
    }

    /**
     * 退出登录，使当前已有的 Session 失效。
     *
     * <p>请求：{@code POST /api/auth/logout}。未登录时调用也能成功，不创建新 Session。</p>
     *
     * @param httpRequest 当前 HTTP 请求，用于查找并使已有 Session 失效
     * @return 204，响应体为空
     */
    @Operation(
            summary = "退出登录",
            description = "使已有 Session 失效；未登录时调用也能成功，不创建新 Session。",
            responses = {
                    @ApiResponse(responseCode = "204", description = "退出成功，响应体为空")
            })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        currentUserService.logout(httpRequest);
        return ResponseEntity.noContent().build();
    }
}
