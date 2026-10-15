package com.syf.blog.service;

import com.syf.blog.domain.BlogUser;
import com.syf.blog.dto.category.CategoryResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.repository.CategoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 分类业务：公开查询启用分类，以及管理员查询全部分类。 */
@Service
@Transactional(readOnly = true)
public class CategoryService {
    private final CategoryRepository categories;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    /**
     * 查询前台可用分类。
     *
     * @return 启用的分类，按名称升序；无数据时为空列表
     */
    public List<CategoryResponse> listEnabled() {
        return categories.findByEnabledTrueOrderByNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /**
     * 管理员查询全部分类，权限检查在业务层执行。
     *
     * @param loginUser 从服务端会话确认的当前用户，禁止使用客户端传入的身份
     * @return 启用和停用的分类，按名称升序
     * @throws BusinessException 身份无效为 401，非管理员为 403
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
