package com.syf.blog.dto.article;

import com.syf.blog.domain.ContentFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 编辑文章的 JSON 请求体。
 *
 * @param title 标题，不能为空或全为空白，最多 200 个字符，保存时去掉两端空白
 * @param content 正文；TEXT 保留原文，HTML 按编辑器支持的标签清理
 * @param contentFormat 正文格式，TEXT 或 HTML；省略时为 TEXT
 */
public record UpdateArticleRequest(
        @Schema(description = "文章标题，保存时去掉两端空白", example = "我的第一篇博客")
        @NotBlank @Size(max = 200) String title,
        @Schema(description = "文章正文，保留原始内容和换行", example = "记录今天的学习内容。")
        @NotBlank String content,
        @Schema(description = "正文格式；省略为 TEXT，富文本编辑器传 HTML", defaultValue = "TEXT")
        ContentFormat contentFormat
) {
}
