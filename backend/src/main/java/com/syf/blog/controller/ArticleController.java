package com.syf.blog.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.syf.blog.domain.Article;
import com.syf.blog.repository.ArticleRepository;
import com.syf.blog.repository.BlogUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.syf.blog.domain.BlogUser;

/**
 * 文章接口，提供发布、编辑、删除、详情、列表和分页查询功能。
 *
 * <p>基础路径：{@code /api/articles}。查询接口允许匿名访问；写入接口使用 Session 验证身份。</p>
 */
@RestController
@RequestMapping("/api/articles")
public class ArticleController {
    private final ArticleRepository articles;
    private final BlogUserRepository users;

    /**
     * 创建文章控制器，由 Spring 注入数据仓库。
     *
     * @param articles 文章数据仓库
     * @param users 用户数据仓库，用于核查登录用户、账号状态和角色
     */
    public ArticleController(ArticleRepository articles, BlogUserRepository users) {
        this.articles = articles;
        this.users = users;
    }

    /**
     * 从已有 Session 中读取用户 ID，并查询用户当前的账号状态和角色。
     *
     * <p>用户不存在或账号停用时使 Session 失效；此方法不会创建新 Session。</p>
     *
     * @param httpRequest 当前 HTTP 请求，用于读取 Session 中的 {@code loginUserId}
     * @return 仍有效的登录用户；未登录、用户不存在或账号停用时返回 {@code null}
     */
    private BlogUser getLoginUser(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);

        if (session == null
                || !(session.getAttribute("loginUserId") instanceof Long userId)) {
            return null;
        }

        Optional<BlogUser> result = users.findById(userId);

        // 用户不存在，或者账号已停用
        if (result.isEmpty()
                || !Boolean.TRUE.equals(result.get().getEnabled())) {
            session.invalidate();
            return null;
        }

        return result.get();
    }

    /**
     * 判断已登录用户是否有权编辑或删除指定文章。
     *
     * @param user 已确认存在且启用的登录用户
     * @param article 已查询到的文章
     * @return 用户角色为 {@code ADMIN} 或用户为文章作者时返回 {@code true}
     */
    private boolean canManageArticle(BlogUser user, Article article) {
        boolean isAdmin = "ADMIN".equals(user.getRoleCode());
        boolean isAuthor = user.getId().equals(article.getAuthorId());

        return isAdmin || isAuthor;
    }

    /**
     * 发布文章的 JSON 请求体。
     *
     * <p>作者 ID 从 Session 获取；请求中额外传入的 {@code authorId} 会被忽略。</p>
     *
     * @param title 标题，不能为空或全为空白，最多 200 个字符，保存时去掉两端空白
     * @param content 正文，不能为空或全为空白，保存时保留原文
     * @param categoryId 分类 ID，必填且必须为正数，对应数据库中已存在的分类
     * @param coverUrl 封面地址，可省略或为 {@code null}，最多 1024 个字符
     */
    @JsonIgnoreProperties("authorId")
    public record CreateArticleRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank String content,
            @NotNull @Positive Long categoryId,
            @Size(max = 1024) String coverUrl
    ) {
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
    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody CreateArticleRequest request,
            HttpServletRequest httpRequest) {

        BlogUser loginUser = getLoginUser(httpRequest);

        if (loginUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "请先登录或登录状态已失效"));
        }

        // 把请求数据装入实体对象
        Article article = new Article();
        article.setTitle(request.title().trim());
        article.setContent(request.content());
        article.setAuthorId(loginUser.getId());
        article.setCategoryId(request.categoryId());
        article.setCoverUrl(request.coverUrl());

        try {
            // 保存到数据库
            Article saved = articles.save(article);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of(
                            "id", saved.getId(),
                            "title", saved.getTitle()
                    ));
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message",
                            "作者或分类不存在，或文章数据不符合数据库要求"
                    ));
        }
    }


    /**
     * 编辑文章的 JSON 请求体。
     *
     * @param title 标题，不能为空或全为空白，最多 200 个字符，保存时去掉两端空白
     * @param content 正文，不能为空或全为空白，保存时保留原文
     */
    public record UpdateArticleRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank String content
    ) {
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
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateArticleRequest request,
            HttpServletRequest httpRequest) {

        BlogUser loginUser = getLoginUser(httpRequest);

        if (loginUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "请先登录或登录状态已失效"));
        }

        // 先查询要修改的文章
        Optional<Article> result = articles.findById(id);

        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "文章不存在"));
        }

        Article article = result.get();
        if (!canManageArticle(loginUser, article)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "只能编辑自己的文章"));
        }

        // 确认是管理员或作者本人后，修改已有文章的字段
        article.setTitle(request.title().trim());
        article.setContent(request.content());

        // 保存修改
        Article saved = articles.save(article);

        return ResponseEntity.ok(
                Map.of(
                        "id", saved.getId(),
                        "title", saved.getTitle()
                )
        );
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
    @GetMapping
    public List<Article> list(
            @RequestParam(required = false) Long categoryId
    ) {
        //指定分类，查询全部文章
        if(categoryId == null){
            return articles.findAllByOrderByCreatedAtDesc();
        }
        return  articles.findByCategoryIdOrderByCreatedAt(categoryId );
    }

    /**
     * 根据文章 ID 查询详情。
     *
     * <p>请求：{@code GET /api/articles/{id}}，允许匿名访问。此接口只查询，不增加浏览量。</p>
     *
     * @param id 路径参数，待查询的文章 ID
     * @return 文章存在时返回 200 和文章对象；不存在时返回 404，响应体为空
     */
    @GetMapping("/{id}")
    public ResponseEntity<Article> detail(@PathVariable Long id) {

        Optional<Article> article = articles.findById(id);

        // 没找到，返回 HTTP 404
        if (article.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // 找到了，返回 HTTP 200 和文章数据
        return ResponseEntity.ok(article.get());
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
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {

        BlogUser loginUser = getLoginUser(httpRequest);

        if (loginUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "请先登录或登录状态已失效"));
        }

        Optional<Article> result = articles.findById(id);

        if(result.isEmpty()){
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("message","文章不存在")
            );
        }

        Article article = result.get();
        if (!canManageArticle(loginUser, article)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "只能删除自己的文章"));
        }

        try{
            articles.delete(article);
            return  ResponseEntity.noContent().build();
        }catch (DataIntegrityViolationException exception){
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("message","文章仍有评论或收藏，无法删除")
            );
        }
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
    @GetMapping("/page")
    public ResponseEntity<?> page(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size,
            @RequestParam(required = false) Long categoryId) {

        // 检查分页参数
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message",
                            "页码不能小于 0，每页数量必须在 1 到 100 之间"
                    ));
        }

        // 按创建时间、ID 倒序排列
        PageRequest pageRequest = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id")
        );

        Page<Article> result;

        if (categoryId == null) {
            result = articles.findAll(pageRequest);
        } else {
            result = articles.findByCategoryId(categoryId, pageRequest);
        }

        return ResponseEntity.ok(Map.of(
                "content", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        ));
    }

}
