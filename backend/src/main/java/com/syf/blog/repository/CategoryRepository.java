package com.syf.blog.repository;

import com.syf.blog.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
/**
 * 分类数据访问接口，由 Spring Data JPA 自动生成实现，主键类型为 {@link Long}。
 *
 * <p>公开分类列表与管理员分类列表使用不同查询；管理员身份由业务 Service 校验。
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {
    /**
     * 查询 {@code enabled = true} 的分类，并按名称升序排列。
     *
     * <p>{@code EnabledTrue} 指定启用条件，{@code OrderByNameAsc} 指定名称升序。
     *
     * @return 已启用的分类列表；没有匹配分类时返回空列表
     */
    List<Category> findByEnabledTrueOrderByNameAsc();

    /**
     * 查询全部分类，包含启用和停用的分类，按名称升序排列。
     *
     * @return 包含启用和停用分类的列表；没有分类时返回空列表
     */
    List<Category> findAllByOrderByNameAsc();


}
