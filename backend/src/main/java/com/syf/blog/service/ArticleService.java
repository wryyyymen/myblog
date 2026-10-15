package com.syf.blog.service;

import com.syf.blog.domain.Article;
import com.syf.blog.domain.BlogUser;
import com.syf.blog.dto.article.CreateArticleRequest;
import com.syf.blog.dto.article.UpdateArticleRequest;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.repository.ArticleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 文章业务：组织查询、发布、编辑、删除，并检查文章操作权限。
 * 登录身份由 CurrentUserService 从 Session 和数据库取得后传入。
 */
@Service
@Transactional(readOnly = true)
public class ArticleService {
    private final ArticleRepository articles;
    private final ArticleContentService articleContentService;

    public ArticleService(ArticleRepository articles, ArticleContentService articleContentService) {
        this.articles = articles;
        this.articleContentService = articleContentService;
    }

    /**
     * 发布文章，作者使用已验证的登录用户。
     *
     * @param request 已通过接口参数校验的文章数据
     * @param loginUser 从服务端会话获取的有效用户，不能使用客户端提交的用户对象
     * @return 保存后的文章
     * @throws BusinessException 身份无效时为 401，数据库约束不满足时为 400
     */
    @Transactional
    public Article create(CreateArticleRequest request, BlogUser loginUser) {
        requireActiveUser(loginUser);
        Article article = new Article();
        article.setTitle(request.title().trim());
        article.setContent(articleContentService.normalize(request.content(), request.contentFormat()));
        article.setContentFormat(articleContentService.resolveFormat(request.contentFormat()));
        article.setAuthorId(loginUser.getId());
        article.setCategoryId(request.categoryId());
        article.setCoverUrl(request.coverUrl());

        try {
            // 在业务方法内执行 SQL，使数据库约束异常能在这里转换。
            return articles.saveAndFlush(article);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "作者或分类不存在，或文章数据不符合数据库要求", exception);
        }
    }

    /**
     * 修改文章标题和正文。
     *
     * @param id 文章 ID
     * @param request 已通过接口参数校验的修改内容
     * @param loginUser 服务端确认的当前用户
     * @return 修改后的文章，原作者和分类保持原值
     * @throws BusinessException 身份无效为 401，无操作权限为 403，文章不存在为 404
     */
    @Transactional
    public Article update(Long id, UpdateArticleRequest request, BlogUser loginUser) {
        requireActiveUser(loginUser);
        Article article = requireArticle(id);
        requireManagePermission(loginUser, article, "只能编辑自己的文章");
        article.setTitle(request.title().trim());
        article.setContent(articleContentService.normalize(request.content(), request.contentFormat()));
        article.setContentFormat(articleContentService.resolveFormat(request.contentFormat()));
        return articles.saveAndFlush(article);
    }

    /**
     * 删除文章；关联评论或收藏触发的约束异常转换为业务提示。
     *
     * @param id 文章 ID
     * @param loginUser 服务端确认的当前用户
     * @throws BusinessException 身份无效为 401，无操作权限为 403，
     *                          文章不存在为 404，存在关联数据为 409
     */
    @Transactional
    public void delete(Long id, BlogUser loginUser) {
        requireActiveUser(loginUser);
        Article article = requireArticle(id);
        requireManagePermission(loginUser, article, "只能删除自己的文章");

        try {
            articles.delete(article);
            // JPA 可能延迟执行 DELETE；主动刷新才能在本方法内捕获外键异常。
            articles.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "文章仍有评论或收藏，无法删除", exception);
        }
    }

    /**
     * 查询不分页的文章列表。
     *
     * @param categoryId 可选分类 ID；省略时按创建时间降序，指定时沿用原来的升序
     * @return 匹配的文章，没有匹配数据时为空列表
     */
    public List<Article> list(Long categoryId) {
        return categoryId == null
                ? articles.findAllByOrderByCreatedAtDesc()
                : articles.findByCategoryIdOrderByCreatedAt(categoryId);
    }

    /**
     * 查询文章详情，不增加浏览量。
     *
     * @param id 文章 ID
     * @return 文章；不存在时返回空 Optional，由接口返回空响应体的 404
     */
    public Optional<Article> detail(Long id) {
        return articles.findById(id);
    }

    /**
     * 分页查询文章，按创建时间及 ID 倒序。
     *
     * @param page 从 0 开始的页码
     * @param size 每页数量，范围 1～100
     * @param categoryId 可选分类 ID
     * @return 含文章数据和分页信息的结果
     * @throws BusinessException 分页参数不合法时为 400
     */
    public Page<Article> page(int page, int size, Long categoryId) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "页码不能小于 0，每页数量必须在 1 到 100 之间");
        }

        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return categoryId == null
                ? articles.findAll(pageable)
                : articles.findByCategoryId(categoryId, pageable);
    }

    private Article requireArticle(Long id) {
        return articles.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "文章不存在"));
    }

    /** 操作权限放在业务层，所有调用方都必须经过检查。 */
    private void requireManagePermission(BlogUser user, Article article, String message) {
        if (!"ADMIN".equals(user.getRoleCode()) && !user.getId().equals(article.getAuthorId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, message);
        }
    }

    /** 防止业务方法被其他服务以空身份或停用账号调用。 */
    private void requireActiveUser(BlogUser user) {
        if (user == null || user.getId() == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录或登录状态已失效");
        }
    }
}
