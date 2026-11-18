package com.syf.blog.repository;

import com.syf.blog.domain.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 文章数据访问接口，由 Spring Data JPA 根据方法名生成查询实现。
 *
 * <p>继承的增删改查方法操作 {@link Article} 实体，主键类型为 {@link Long}。
 * 文章发布、编辑和删除权限由业务 Service 检查，Repository 负责数据访问。
 */
public interface ArticleRepository extends JpaRepository<Article,Long> {
    /**
     * 查询全部文章，按创建时间从新到旧排列。
     *
     * @return 按创建时间降序排列的文章列表；没有文章时返回空列表
     */
    List<Article> findAllByOrderByCreatedAtDesc();

    /**
     * 查询指定分类的文章，按创建时间从旧到新排列。
     *
     * <p>方法名中的 {@code OrderByCreatedAt} 省略了方向，默认使用升序。
     * 本查询只匹配文章的分类 ID，不校验分类是否存在或启用。
     *
     * @param categoryId 要匹配的分类 ID，由调用方提供
     * @return 分类下按创建时间升序排列的文章；没有匹配文章时返回空列表
     */
    List<Article>  findByCategoryIdOrderByCreatedAt(Long categoryId);

    /**
     * 按调用方给定的分页和排序条件查询指定分类的文章。
     *
     * @param categoryId 要匹配的分类 ID
     * @param pageable 分页条件，包含从 0 开始的页码、每页数量以及排序规则
     * @return 包含本页文章、文章总数和总页数的结果；没有匹配文章时本页内容为空
     */
    Page<Article> findByCategoryId(Long categoryId, Pageable pageable);
}
