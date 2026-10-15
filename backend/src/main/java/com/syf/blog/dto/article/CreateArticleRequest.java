package com.syf.blog.dto.article;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.syf.blog.domain.ContentFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 发布文章的 JSON 请求体。
 *
 * <p>作者 ID 从 Session 获取；请求中额外传入的 {@code authorId} 会被忽略。</p>
 *
 * @param title 标题，不能为空或全为空白，最多 200 个字符，保存时去掉两端空白
 * @param content 正文；TEXT 保留原文，HTML 按编辑器支持的标签清理
 * @param contentFormat 正文格式，TEXT 或 HTML；省略时为 TEXT
 * @param categoryId 分类 ID，必填且必须为正数，对应数据库中已存在的分类
 * @param coverUrl 封面地址，可省略或为 {@code null}，最多 1024 个字符
 */
@JsonIgnoreProperties("authorId")
public record CreateArticleRequest(
        @Schema(description = "文章标题，保存时去掉两端空白", example = "我的第一篇博客")
        @NotBlank @Size(max = 200) String title,
        @Schema(description = "文章正文，保留原始内容和换行", example = "记录今天的学习内容。")
        @NotBlank String content,
        @Schema(description = "已存在的分类 ID", example = "1")
        @NotNull @Positive Long categoryId,
        @Schema(description = "可选封面地址，最多 1024 个字符", example = "https://example.com/cover.jpg")
        @Size(max = 1024) String coverUrl,
        @Schema(description = "正文格式；省略为 TEXT，富文本编辑器传 HTML", defaultValue = "TEXT")
        ContentFormat contentFormat
) {
}
