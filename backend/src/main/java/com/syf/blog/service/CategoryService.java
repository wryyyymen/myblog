package com.syf.blog.service;

import com.syf.blog.domain.BlogUser;
import com.syf.blog.dto.category.CategoryResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.repository.CategoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分类查询业务服务，负责公开分类列表和管理员分类列表。
 *
 * <p>查询使用只读事务；管理员查询在业务层再次检查可信用户的身份和角色，
 * 返回结果统一转换成分类响应 DTO。
 */
@Service
@Transactional(readOnly = true)
public class CategoryService {
    private final CategoryRepository categories;

    /**
     * 创建分类服务，由 Spring 注入分类数据访问组件。
     *
     * @param categories 分类 Repository，用于按名称排序查询分类
     */
    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    /**
     * 公开查询前台可用的启用分类。
     *
     * <p>该方法不要求登录，用于文章页面的分类展示和筛选。
     *
     * @return 启用分类的响应 DTO 列表，按名称升序；没有数据时返回空列表
     */
    public List<CategoryResponse> listEnabled() {
        return categories.findByEnabledTrueOrderByNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /**
     * 管理员查询全部分类，包括已停用的分类。
     *
     * <p>先确认用户对象具有 ID 且账号启用，再要求角色为 ADMIN。
     *
     * @param loginUser 从服务端会话确认的当前用户，禁止使用客户端传入的身份
     * @return 启用和停用分类的响应 DTO 列表，按名称升序；没有数据时返回空列表
     * @throws BusinessException 用户身份无效或账号未启用时携带 401，角色不是 ADMIN 时携带 403
     */
    public List<CategoryResponse> listAll(BlogUser loginUser) {
        if (loginUser == null || loginUser.getId() == null
                || !Boolean.TRUE.equals(loginUser.getEnabled())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录或登录状态已失效");
        }
        if (!"ADMIN".equals(loginUser.getRoleCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有管理员可以管理分类");
        }
        return categories.findAllByOrderByNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
