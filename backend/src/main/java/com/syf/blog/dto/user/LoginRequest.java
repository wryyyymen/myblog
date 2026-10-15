package com.syf.blog.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录账号的 JSON 请求体。
 *
 * @param username 用户名，不能为空或全为空白，查询时去掉两端空白
 * @param password 明文密码，不能为空或全为空白，用于与数据库中的密码哈希校验
 */
public record LoginRequest(
        @Schema(description = "用户名，去掉两端空白后使用", example = "blog_reader")
        @NotBlank String username,
        @Schema(description = "登录密码", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank String password
){}
