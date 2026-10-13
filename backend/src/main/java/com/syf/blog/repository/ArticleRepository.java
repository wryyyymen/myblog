package com.syf.blog.repository;

import com.syf.blog.domain.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ArticleRepository extends JpaRepository<Article,Long> {
    List<Article> findAllByOrderByCreatedAtDesc();
    List<Article>  findByCategoryIdOrderByCreatedAt(Long categoryId);
    // 分页查询某个分类的文章，Page<Article> 包含本页文章、文章总数和总页数。Pageable 指定页码、每页数量及排序。
    Page<Article> findByCategoryId(Long categoryId, Pageable pageable);
}
