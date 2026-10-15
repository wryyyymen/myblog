package com.syf.blog.dto.user;

import com.syf.blog.domain.BlogUser;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录和当前用户接口返回的公开信息。
 *
 * @param id 用户 ID
 * @param username 用户名
 * @param role 数据库中当前角色，USER 或 ADMIN
 */
public record UserResponse(
        @Schema(description = "用户 ID", example = "7") Long id,
        @Schema(description = "用户名", example = "blog_reader") String username,
        @Schema(description = "角色", allowableValues = {"USER", "ADMIN"}) String role
) {
    /**
     * @param user 后端查询得到的用户实体
     * @return 仅包含公开字段的响应
     */
    public static UserResponse from(BlogUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRoleCode());
    }
}
