package com.syf.blog.service;

import com.syf.blog.domain.ContentFormat;
import com.syf.blog.exception.BusinessException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 富文本正文校验和清理，与 HTTP 接口及数据库访问分离。 */
@Service
public class ArticleContentService {
    private static final Safelist ALLOWED = new Safelist()
            .addTags("p", "br", "h2", "h3", "h4", "strong", "em", "s", "u",
                    "ul", "ol", "li", "blockquote", "pre", "code", "hr", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
            .addAttributes("ol", "start")
            .addAttributes("code", "class");

    /** @param format 请求格式；@return 缺省格式为 TEXT，兼容原有接口 */
    public ContentFormat resolveFormat(ContentFormat format) {
        return format == null ? ContentFormat.TEXT : format;
    }

    /**
     * 校验正文，富文本按允许的标签和属性清理。
     *
     * @param content 原始正文
     * @param format 格式，缺省按纯文本处理
     * @return 可保存的正文；纯文本保留原文
     * @throws BusinessException 正文为空或清理后无有效文字时为 400
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
        String text = document.body().text().replace('\u00a0', ' ').replace("\u200b", "");
        if (text.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入正文内容");
        }
        return document.body().html();
    }
}
