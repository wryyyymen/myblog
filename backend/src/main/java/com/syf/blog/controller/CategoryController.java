package com.syf.blog.controller;

import com.syf.blog.domain.Category;
import com.syf.blog.repository.CategoryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类查询接口，供文章列表筛选和发布文章时选择分类使用。
 *
 * <p>基础路径：{@code /api/categories}，允许匿名访问。</p>
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryRepository categories;

    /**
     * 创建分类控制器，由 Spring 注入分类仓库。
     *
     * @param categories 分类数据仓库
     */
    public CategoryController(CategoryRepository categories){
        this.categories = categories;
    }

    /**
     * 查询所有启用的分类，并按分类名称升序排列。
     *
     * <p>请求：{@code GET /api/categories}，无请求参数，允许匿名访问。</p>
     *
     * @return 200 和分类对象数组；没有启用的分类时返回空数组
     */
    @GetMapping
    public List<Category> list(){
        return categories.findByEnabledTrueOrderByNameAsc();
    }


}
