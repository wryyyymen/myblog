package com.syf.blog.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 注册成功后的公开信息。
 *
 * @param id 用户 ID
 * @param username 用户名
 */
public record RegisterResponse(
        @Schema(description = "用户 ID", example = "7") Long id,
        @Schema(description = "用户名", example = "blog_reader") String username
) {}
