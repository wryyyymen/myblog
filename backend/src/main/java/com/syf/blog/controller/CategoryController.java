package com.syf.blog.controller;

import com.syf.blog.auth.CurrentUserService;
import com.syf.blog.domain.BlogUser;
import com.syf.blog.dto.category.CategoryResponse;
import com.syf.blog.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 分类查询与管理接口。
 *
 * <p>基础路径：{@code /api/categories}。前台分类列表允许匿名访问，
 * 管理接口仅允许启用的管理员访问。</p>
 *
 * <p>登录身份由 {@link CurrentUserService} 从 Session 和数据库读取；
 * ADMIN 角色检查由 {@link CategoryService} 执行。
 * 对外返回分类响应 DTO，避免将数据库实体作为接口参数或响应结构。</p>
 */
@Tag(name = "分类管理", description = "前台分类查询和管理员分类查询")
@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService categoryService;
    private final CurrentUserService currentUserService;

    /**
     * 创建分类控制器，由 Spring 注入分类业务和当前用户查询组件。
     *
     * @param categoryService 分类业务服务
     * @param currentUserService 基于 Session 的当前用户查询组件
     */
    public CategoryController(
            CategoryService categoryService,
            CurrentUserService currentUserService
    ) {
        this.categoryService = categoryService;
        this.currentUserService = currentUserService;
    }

    /**
     * 查询所有启用的分类，并按分类名称升序排列。
     *
     * <p>请求：{@code GET /api/categories}，无请求参数，允许匿名访问。</p>
     *
     * @return 200 和分类响应对象数组；每项包含分类 ID、名称、说明、启用状态及创建、更新时间，
     *         没有启用的分类时返回空数组
     */
    @Operation(
            summary = "查询启用的分类",
            description = "允许匿名访问。返回已启用的分类，按名称升序，供前台筛选和发布文章使用。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "分类数组，没有数据时返回空数组")
            })
    @GetMapping
    public List<CategoryResponse> list() {
        return categoryService.listEnabled();
    }

    /**
     * 管理员查询全部分类，包括已停用的分类，按名称升序排列。
     *
     * <p>请求：{@code GET /api/categories/admin}。
     * 先读取服务端登录身份，再由业务层检查数据库中的当前角色是否为 ADMIN。</p>
     *
     * @param request 当前 HTTP 请求，用于从已有 Session 中取得可信登录用户
     * @return 成功返回 200 和分类数组，无分类时为空数组；登录状态无效返回 401；
     *         非管理员返回 403
     * @throws com.syf.blog.exception.BusinessException 登录身份失效，或当前用户不是管理员
     */
    @Operation(
            summary = "管理员查询全部分类",
            description = "仅启用的 ADMIN 账号可访问。包含启用和停用的分类，按名称升序。权限以数据库中的当前角色为准。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "全部分类数组"),
                    @ApiResponse(responseCode = "401", description = "未登录或登录状态失效"),
                    @ApiResponse(responseCode = "403", description = "当前用户不是管理员")
            })
    @GetMapping("/admin")
    public ResponseEntity<List<CategoryResponse>> adminList(HttpServletRequest request) {
        BlogUser loginUser = currentUserService.requireLogin(request);
        return ResponseEntity.ok(categoryService.listAll(loginUser));
    }

}
