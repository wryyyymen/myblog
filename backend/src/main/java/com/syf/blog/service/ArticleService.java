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
 * 文章业务服务，负责文章查询、发布、编辑、删除及资源操作权限检查。
 *
 * <p>写操作接收调用方通过 CurrentUserService 确认的登录用户，
 * 作者身份来自服务端；编辑和删除仅允许文章作者或管理员执行。
 * 类级别默认使用只读事务，发布、编辑、删除方法分别开启写事务。
 */
@Service
@Transactional(readOnly = true)
public class ArticleService {
    private final ArticleRepository articles;
    private final ArticleContentService articleContentService;

    /**
     * 创建文章业务服务，由 Spring 注入文章数据访问和正文处理组件。
     *
     * @param articles 文章 Repository，用于查询、保存、删除及刷新数据库变更
     * @param articleContentService 正文格式解析、有效内容校验和 HTML 清理服务
     */
    public ArticleService(ArticleRepository articles, ArticleContentService articleContentService) {
        this.articles = articles;
        this.articleContentService = articleContentService;
    }

    /**
     * 在写事务中发布文章，并将当前登录用户记录为作者。
     *
     * <p>标题去掉两端空白；正文按请求格式校验并清理；
     * 分类和封面取自请求。保存时主动刷新数据库，
     * 将本方法捕获到的数据库约束冲突转换为业务异常。
     *
     * @param request 已通过接口参数校验的文章数据，包含标题、正文、分类、封面和可选正文格式
     * @param loginUser 从服务端会话获取的有效用户，不能使用客户端提交的用户对象
     * @return 保存后的文章实体，包含数据库生成的文章 ID
     * @throws BusinessException 身份无效时携带 401；正文无有效内容或本方法捕获到数据库约束冲突时携带 400
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
     * 在写事务中修改文章标题、正文及正文格式。
     *
     * <p>先检查登录身份、文章是否存在及管理员/作者权限，
     * 再规范标题、处理正文并刷新保存。未传正文格式时按 TEXT 处理。
     *
     * @param id 文章 ID
     * @param request 已通过接口参数校验的修改内容
     * @param loginUser 服务端确认的当前用户
     * @return 修改后的文章实体，作者、分类和封面保持原值
     * @throws BusinessException 身份无效时携带 401，无操作权限时携带 403，
     *                          文章不存在时携带 404，正文无有效内容时携带 400
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
     * 在写事务中删除文章，仅允许文章作者或管理员操作。
     *
     * <p>删除后主动刷新数据库。删除或刷新触发数据库约束冲突时，
     * 转换为“文章仍有评论或收藏，无法删除”的 409 业务异常；失败事务回滚。
     *
     * @param id 文章 ID
     * @param loginUser 服务端确认的当前用户
     * @throws BusinessException 身份无效时携带 401，无操作权限时携带 403，
     *                          文章不存在时携带 404，捕获到数据库约束冲突时携带 409
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
     * 公开查询不分页的文章列表，可按分类筛选。
     *
     * @param categoryId 可选分类 ID；为 null 时查询全部并按创建时间降序，指定时按该分类下文章的创建时间升序
     * @return 匹配的文章列表；没有匹配文章时为空列表，也不会因分类没有匹配数据而抛出 404
     */
    public List<Article> list(Long categoryId) {
        return categoryId == null
                ? articles.findAllByOrderByCreatedAtDesc()
                : articles.findByCategoryIdOrderByCreatedAt(categoryId);
    }

    /**
     * 公开查询单篇文章详情，查询过程不增加浏览量。
     *
     * @param id 文章 ID
     * @return 包含匹配文章的 Optional；不存在时返回 Optional.empty，由调用方组织未找到响应
     */
    public Optional<Article> detail(Long id) {
        return articles.findById(id);
    }

    /**
     * 公开分页查询文章，可按分类筛选，按创建时间及 ID 倒序排列。
     *
     * <p>排序中的 ID 用于区分创建时间相同的文章；查询结果为空时正常返回空页。
     *
     * @param page 从 0 开始的页码
     * @param size 每页数量，范围 1～100
     * @param categoryId 可选分类 ID，为 null 时查询全部分类
     * @return 含当前页文章、总条数及分页信息的 Page 对象
     * @throws BusinessException 页码小于 0，或每页数量不在 1～100 之间时抛出，携带 400 状态
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

    /**
     * 按 ID 取得必须存在的文章，供编辑和删除流程复用。
     *
     * @param id 需要操作的文章 ID
     * @return 数据库中的文章实体
     * @throws BusinessException 文章不存在时抛出，携带 404 状态
     */
    private Article requireArticle(Long id) {
        return articles.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "文章不存在"));
    }

    /**
     * 检查当前用户是否为管理员或文章作者。
     *
     * @param user 已经通过登录有效性检查的用户
     * @param article 本次需要编辑或删除的文章
     * @param message 无权限时向客户端展示的业务提示
     * @throws BusinessException 用户既非管理员也非作者时抛出，携带 403 状态
     */
    private void requireManagePermission(BlogUser user, Article article, String message) {
        if (!"ADMIN".equals(user.getRoleCode()) && !user.getId().equals(article.getAuthorId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, message);
        }
    }

    /**
     * 检查业务方法接收的用户对象是否具有可用身份。
     *
     * <p>该方法检查传入对象的 ID 和启用状态；调用方应先通过服务端会话取得可信用户。
     *
     * @param user 调用方传入的当前用户
     * @throws BusinessException 用户为 null、ID 为 null 或账号未启用时抛出，携带 401 状态
     */
    private void requireActiveUser(BlogUser user) {
        if (user == null || user.getId() == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录或登录状态已失效");
        }
    }
}
