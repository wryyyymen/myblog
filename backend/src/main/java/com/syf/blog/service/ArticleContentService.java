package com.syf.blog.service;

import com.syf.blog.domain.ContentFormat;
import com.syf.blog.exception.BusinessException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 文章正文处理服务，负责确定存储格式、检查有效文字和清理 HTML。
 *
 * <p>纯文本校验通过后保留原文；HTML 仅保留编辑器支持的标签、属性及链接协议。
 * 本服务只处理正文字符串，不读取 HTTP 会话或执行数据库操作。
 */
@Service
public class ArticleContentService {
    // 富文本允许列表：保留基础排版、链接和代码块，过滤其他标签和属性。
    private static final Safelist ALLOWED = new Safelist()
            .addTags("p", "br", "h2", "h3", "h4", "strong", "em", "s", "u",
                    "ul", "ol", "li", "blockquote", "pre", "code", "hr", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
            .addAttributes("ol", "start")
            .addAttributes("code", "class");

    /**
     * 确定正文采用的存储格式。
     *
     * @param format 请求声明的正文格式，可以为 null
     * @return 请求中的格式；为 null 时返回 TEXT，以兼容原有纯文本接口
     */
    public ContentFormat resolveFormat(ContentFormat format) {
        return format == null ? ContentFormat.TEXT : format;
    }

    /**
     * 校验并规范正文，返回适合保存的内容。
     *
     * <p>TEXT 内容保留原文和换行。HTML 内容先按允许列表清理，
     * 再移除不支持的代码语言 class，并确认清理后仍有有效文字；
     * 最终返回正文片段，不额外美化 HTML 排版。
     *
     * @param content 原始正文字符串，必须包含有效内容
     * @param format 正文格式；为 null 时按 TEXT 处理
     * @return 纯文本原文，或经过清理的 HTML 正文片段
     * @throws BusinessException 正文为 null、全为空白，或 HTML 清理后没有有效文字时抛出，携带 400 状态
     */
    public String normalize(String content, ContentFormat format) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入正文内容");
        }
        if (resolveFormat(format) == ContentFormat.TEXT) {
            return content;
        }

        String cleaned = Jsoup.clean(content, "", ALLOWED,
                new Document.OutputSettings().prettyPrint(false));
        Document document = Jsoup.parseBodyFragment(cleaned);
        document.outputSettings().prettyPrint(false);
        for (Element code : document.select("code[class]")) {
            String language = code.className();
            if (!language.matches("language-(plaintext|java|javascript|typescript|python|sql|bash|json|xml|css)")) {
                code.removeAttr("class");
            }
        }
        // 检查有效文字时统一处理不换行空格和零宽空格，避免将视觉空白当成正文。
        String text = document.body().text().replace('\u00a0', ' ').replace("\u200b", "");
        if (text.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入正文内容");
        }
        return document.body().html();
    }
}
