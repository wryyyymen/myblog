package com.syf.blog.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "blogs")
@Data
public class Article {

    // 文章编号，由数据库自动生成
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "BIGINT UNSIGNED")
    private Long id;

    // 作者 ID，对应 users 表的 id
    @Column(name = "author_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long authorId;

    // 分类 ID，对应 categories 表的 id
    @Column(name = "category_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long categoryId;

    // 标题，最多 200 个字符
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    // 封面地址，可以为空
    @Column(name = "cover_url", length = 1024)
    private String coverUrl;

    // 正文，与数据库的 LONGTEXT 类型保持一致
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    // 显式区分历史纯文本和富文本，避免根据内容猜测格式。
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "content_format", nullable = false, length = 16,
            columnDefinition = "VARCHAR(16) DEFAULT 'TEXT'")
    private ContentFormat contentFormat = ContentFormat.TEXT;

    // 阅读量，新文章默认从 0 开始
    @Column(name = "view_count", nullable = false,
            columnDefinition = "BIGINT UNSIGNED DEFAULT 0")
    private Long viewCount = 0L;

    // 创建时间由数据库自动填写，JPA 只读取
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)")
    private LocalDateTime createdAt;

    // 更新时间由数据库自动维护，JPA 只读取
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)")
    private LocalDateTime updatedAt;

    // JPA 创建实体对象时需要无参构造方法
    public Article() {
    }

}
