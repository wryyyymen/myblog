package com.syf.blog.controller;

import com.syf.blog.auth.CurrentUserService;
import com.syf.blog.domain.Article;
import com.syf.blog.domain.BlogUser;
import com.syf.blog.dto.article.CreateArticleRequest;
import com.syf.blog.dto.article.UpdateArticleRequest;
import com.syf.blog.service.ArticleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * 文章接口，提供发布、编辑、删除、详情、列表和分页查询功能。
 *
 * <p>基础路径：{@code /api/articles}。查询接口允许匿名访问；写入接口使用 Session 验证身份。</p>
 */
@Tag(name = "文章管理", description = "文章查询、发布、编辑和删除")
@RestController
@RequestMapping("/api/articles")
public class ArticleController {
    private final ArticleService articleService;
    private final CurrentUserService currentUserService;

    /**
     * @param articleService 文章业务服务
     * @param currentUserService 基于 Session 的当前用户查询组件
     */
    public ArticleController(ArticleService articleService, CurrentUserService currentUserService) {
        this.articleService = articleService;
        this.currentUserService = currentUserService;
    }

    /**
     * 发布文章，并将当前登录用户设为作者。
     *
     * <p>请求：{@code POST /api/articles}，请求体格式为 JSON。需要有效且启用的登录账号。</p>
     *
     * @param request JSON 请求体，包含标题、正文、分类 ID 和可选封面地址
     * @param httpRequest 当前 HTTP 请求，用于从 Session 获取登录用户
     * @return 成功返回 201 和文章的 {@code id}、{@code title}；
     *         参数校验失败或数据不符合数据库约束时返回 400；登录状态无效时返回 401
     */
    @Operation(
            summary = "发布文章",
            description = "需要登录。作者由 Session 确定，请求中的 authorId 会被忽略。成功返回 id 和 title。",
            responses = {
                    @ApiResponse(responseCode = "201", description = "文章创建成功，返回 id 和 title"),
                    @ApiResponse(responseCode = "400", description = "请求参数不合法或违反数据库约束"),
                    @ApiResponse(responseCode = "401", description = "未登录或登录状态失效")
            })
    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody CreateArticleRequest request,
            HttpServletRequest httpRequest) {
        BlogUser loginUser = currentUserService.requireLogin(httpRequest);
        Article saved = articleService.create(request, loginUser);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", saved.getId(), "title", saved.getTitle()));
    }

    /**
     * 修改指定文章的标题和正文。
     *
     * <p>请求：{@code PUT /api/articles/{id}}，请求体格式为 JSON。
     * 仅启用的管理员或文章作者本人可以操作。</p>
     *
     * @param id 路径参数，待修改的文章 ID
     * @param request JSON 请求体，包含修改后的标题和正文
     * @param httpRequest 当前 HTTP 请求，用于读取 Session 并核查用户权限
     * @return 成功返回 200 和文章的 {@code id}、{@code title}；参数校验失败返回 400；
     *         登录状态无效返回 401；没有权限返回 403；文章不存在返回 404
     */
    @Operation(
            summary = "编辑文章",
            description = "仅启用的管理员或作者本人可修改标题和正文。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "修改成功，返回 id 和 title"),
                    @ApiResponse(responseCode = "400", description = "请求参数不合法"),
                    @ApiResponse(responseCode = "401", description = "未登录或登录状态失效"),
                    @ApiResponse(responseCode = "403", description = "无权编辑该文章"),
                    @ApiResponse(responseCode = "404", description = "文章不存在")
            })
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @Parameter(description = "文章 ID", example = "1", required = true)
            @PathVariable Long id,
            @Valid @RequestBody UpdateArticleRequest request,
            HttpServletRequest httpRequest) {
        BlogUser loginUser = currentUserService.requireLogin(httpRequest);
        Article saved = articleService.update(id, request, loginUser);
        return ResponseEntity.ok(Map.of("id", saved.getId(), "title", saved.getTitle()));
    }
    /**
     * 查询全部文章或某个分类下的文章，不分页。
     *
     * <p>请求：{@code GET /api/articles}，允许匿名访问。
     * 未指定分类时按创建时间降序；指定分类时按当前仓库方法的创建时间升序返回。</p>
     *
     * @param categoryId 可选查询参数，分类 ID；省略时查询全部文章
     * @return 200 和文章对象数组；没有匹配文章时返回空数组
     */
    @Operation(
            summary = "查询文章列表",
            description = "允许匿名访问。不分页；未指定分类时按创建时间降序，指定分类时按创建时间升序。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "文章数组，没有匹配数据时返回空数组")
            })
    @GetMapping
    public List<Article> list(
            @Parameter(description = "可选分类 ID；省略时查询全部分类", example = "1")
            @RequestParam(required = false) Long categoryId
    ) {
        return articleService.list(categoryId);
    }

    /**
     * 根据文章 ID 查询详情。
     *
     * <p>请求：{@code GET /api/articles/{id}}，允许匿名访问。此接口只查询，不增加浏览量。</p>
     *
     * @param id 路径参数，待查询的文章 ID
     * @return 文章存在时返回 200 和文章对象；不存在时返回 404，响应体为空
     */
    @Operation(
            summary = "查询文章详情",
            description = "允许匿名访问。只查询文章，不增加浏览量。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "文章详情"),
                    @ApiResponse(responseCode = "404", description = "文章不存在，响应体为空")
            })
    @GetMapping("/{id}")
    public ResponseEntity<Article> detail(
            @Parameter(description = "文章 ID", example = "1", required = true)
            @PathVariable Long id) {
        return ResponseEntity.of(articleService.detail(id));
    }

    /**
     * 删除指定文章。
     *
     * <p>请求：{@code DELETE /api/articles/{id}}。
     * 仅启用的管理员或文章作者本人可以操作；存在评论或收藏关联时无法删除。</p>
     *
     * @param id 路径参数，待删除的文章 ID
     * @param httpRequest 当前 HTTP 请求，用于读取 Session 并核查用户权限
     * @return 成功返回 204，响应体为空；登录状态无效返回 401；没有权限返回 403；
     *         文章不存在返回 404；删除触发数据库完整性约束时返回 409
     */
    @Operation(
            summary = "删除文章",
            description = "仅启用的管理员或作者本人可操作。存在评论或收藏关联时无法删除。",
            responses = {
                    @ApiResponse(responseCode = "204", description = "删除成功，响应体为空"),
                    @ApiResponse(responseCode = "401", description = "未登录或登录状态失效"),
                    @ApiResponse(responseCode = "403", description = "无权删除该文章"),
                    @ApiResponse(responseCode = "404", description = "文章不存在"),
                    @ApiResponse(responseCode = "409", description = "文章仍有关联数据，无法删除")
            })
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @Parameter(description = "文章 ID", example = "1", required = true)
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        BlogUser loginUser = currentUserService.requireLogin(httpRequest);
        articleService.delete(id, loginUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * 分页查询全部文章或指定分类的文章。
     *
     * <p>请求：{@code GET /api/articles/page}，允许匿名访问。
     * 按创建时间降序排列，创建时间相同时按文章 ID 降序排列。</p>
     *
     * @param page 查询参数，页码，从 0 开始，默认 0，不能小于 0
     * @param size 查询参数，每页数量，默认 5，范围为 1 到 100
     * @param categoryId 可选查询参数，分类 ID；省略时查询全部分类
     * @return 成功返回 200，包含 {@code content}（本页文章）、{@code page}（页码）、
     *         {@code size}（每页数量）、{@code totalElements}（文章总数）和
     *         {@code totalPages}（总页数）；分页参数不合法时返回 400
     */
    @Operation(
            summary = "分页查询文章",
            description = "允许匿名访问。按 createdAt、id 降序，返回 content、page、size、totalElements 和 totalPages。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "文章分页结果"),
                    @ApiResponse(responseCode = "400", description = "页码、每页数量或参数格式不合法")
            })
    @GetMapping("/page")
    public ResponseEntity<?> page(
            @Parameter(description = "页码，从 0 开始", example = "0", schema = @Schema(minimum = "0"))
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量，范围 1～100", example = "5",
                    schema = @Schema(minimum = "1", maximum = "100"))
            @RequestParam(defaultValue = "5") int size,
            @Parameter(description = "可选分类 ID；省略时查询全部分类", example = "1")
            @RequestParam(required = false) Long categoryId) {
        Page<Article> result = articleService.page(page, size, categoryId);
        return ResponseEntity.ok(Map.of(
                "content", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        ));
    }

}
