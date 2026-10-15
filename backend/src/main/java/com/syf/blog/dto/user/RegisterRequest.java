package com.syf.blog.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册账号的 JSON 请求体。
 *
 * @param username 用户名，不能为空或全为空白，注册时去掉两端空白
 * @param password 明文密码，不能为空或全为空白，至少 8 个字符，保存前使用 BCrypt 编码
 */

public record RegisterRequest(
        @Schema(description = "用户名，去掉两端空白后使用", example = "blog_reader")
        @NotBlank String username,
        @Schema(description = "注册密码，至少 8 个字符", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank @Size(min = 8) String password
){}
