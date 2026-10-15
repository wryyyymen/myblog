package com.syf.blog.dto.category;

import com.syf.blog.domain.Category;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 分类响应，显式列出接口对外提供的字段。
 *
 * @param id 分类 ID
 * @param name 分类名称
 * @param description 分类说明，可为空
 * @param enabled 是否启用
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record CategoryResponse(
        @Schema(description = "分类 ID", example = "1") Long id,
        @Schema(description = "分类名称", example = "学习记录") String name,
        @Schema(description = "分类说明") String description,
        @Schema(description = "是否启用") Boolean enabled,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt
) {
    /**
     * @param category 数据库查询得到的分类实体
     * @return 与现有分类接口字段一致的响应
     */
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getDescription(),
                category.getEnabled(), category.getCreatedAt(), category.getUpdatedAt());
    }
}
