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
     * 将数据库中的用户转换成登录、当前用户接口使用的公开响应。
     *
     * <p>仅映射 ID、用户名和角色，响应不包含实体中的密码哈希。
     * 账号存在性和启用状态由调用方在转换前检查。
     *
     * @param user 后端查询并确认有效的用户实体，调用方应保证非空
     * @return 仅包含公开字段的响应，角色直接取自实体的 roleCode
     */
    public static UserResponse from(BlogUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRoleCode());
    }
}
