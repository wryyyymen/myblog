package com.syf.blog.controller;

import com.syf.blog.domain.Article;
import com.syf.blog.auth.CurrentUserService;
import com.syf.blog.exception.GlobalExceptionHandler;
import com.syf.blog.service.ArticleService;
import com.syf.blog.service.ArticleContentService;
import com.syf.blog.domain.BlogUser;
import com.syf.blog.repository.ArticleRepository;
import com.syf.blog.repository.BlogUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ArticleControllerTest {
    private static final String UPDATE_BODY = """
            {"title":" Updated title ","content":"Updated content"}
            """;
    private static final String CREATE_BODY = """
            {"title":" New article ","content":"Article content","categoryId":1}
            """;

    @Mock
    private ArticleRepository articles;
    @Mock
    private BlogUserRepository users;
    private MockMvc mvc;

    private BlogUser user(Long id, String roleCode) {
        BlogUser user = new BlogUser();
        user.setId(id);
        user.setRoleCode(roleCode);
        user.setEnabled(true);
        return user;
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new ArticleController(new ArticleService(articles, new ArticleContentService()), new CurrentUserService(users)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void anonymousWriteRequestsAreRejectedBeforeAccessingRepositories() throws Exception {
        mvc.perform(post("/api/articles").contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/articles/1").contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/articles/1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(articles, users);
    }

    @Test
    void sessionWithoutUserIdIsNotAuthentication() throws Exception {
        mvc.perform(delete("/api/articles/1").session(new MockHttpSession())
                        .param("authorId", "7"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(articles, users);
    }

    @Test
    void anotherUserCannotEditOrDeleteAnArticle() throws Exception {
        Article article = article();
        when(users.findById(8L)).thenReturn(Optional.of(user(8L, "USER")));
        when(articles.findById(1L)).thenReturn(Optional.of(article));
        MockHttpSession session = session(8L);

        mvc.perform(put("/api/articles/1").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("只能编辑自己的文章"));
        mvc.perform(delete("/api/articles/1").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("只能删除自己的文章"));

        assertEquals("Original title", article.getTitle());
        assertEquals("Original content", article.getContent());
        verify(articles, never()).saveAndFlush(any(Article.class));
        verify(articles, never()).delete(any(Article.class));
    }

    @Test
    void authorCanEditAndDeleteTheirOwnArticle() throws Exception {
        Article article = article();
        when(users.findById(7L))
                .thenReturn(Optional.of(user(7L, "USER")));
        when(articles.findById(1L)).thenReturn(Optional.of(article));
        when(articles.saveAndFlush(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockHttpSession session = session(7L);

        mvc.perform(put("/api/articles/1").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated title"));
        mvc.perform(delete("/api/articles/1").session(session))
                .andExpect(status().isNoContent());

        assertEquals(7L, article.getAuthorId());
        assertEquals("Updated content", article.getContent());
        verify(articles).saveAndFlush(article);
        verify(articles).delete(article);
    }

    @Test
    void publishingUsesSessionUserEvenWhenRequestContainsAnotherAuthorId() throws Exception {
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "USER")));
        when(articles.saveAndFlush(any(Article.class))).thenAnswer(invocation -> {
            Article article = invocation.getArgument(0);
            article.setId(9L);
            return article;
        });
        MockHttpSession session = session(7L);

        // 当前前端不传作者 ID；旧请求或伪造请求传入作者 ID 也不能改变作者。
        for (String body : List.of(CREATE_BODY, """
                {"title":" New article ","content":"Article content","categoryId":1,"authorId":8}
                """)) {
            mvc.perform(post("/api/articles").session(session)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(9));
        }

        ArgumentCaptor<Article> saved = ArgumentCaptor.forClass(Article.class);
        verify(articles, times(2)).saveAndFlush(saved.capture());
        for (Article article : saved.getAllValues()) {
            assertEquals(7L, article.getAuthorId());
            assertEquals("New article", article.getTitle());
        }
    }

    @Test
    void removedUserCannotUseAnOldSession() throws Exception {
        MockHttpSession session = session(7L);
        when(users.findById(7L)).thenReturn(Optional.empty());

        mvc.perform(delete("/api/articles/1").session(session))
                .andExpect(status().isUnauthorized());

        assertTrue(session.isInvalid());
        verifyNoInteractions(articles);
    }

    @Test
    void missingArticleReturnsNotFoundForLoggedInUser() throws Exception {
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "USER")));
        when(articles.findById(1L)).thenReturn(Optional.empty());
        MockHttpSession session = session(7L);

        mvc.perform(put("/api/articles/1").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("文章不存在"));
        mvc.perform(delete("/api/articles/1").session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("文章不存在"));

        verify(articles, never()).saveAndFlush(any(Article.class));
        verify(articles, never()).delete(any(Article.class));
    }

    @ParameterizedTest
    @CsvSource({"7, USER", "9, ADMIN"})
    void authorizedUserStillGetsConflictWhenArticleHasRelatedData(Long userId, String roleCode)
            throws Exception {
        Article article = article();
        when(users.findById(userId)).thenReturn(Optional.of(user(userId, roleCode)));
        when(articles.findById(1L)).thenReturn(Optional.of(article));
        doThrow(new DataIntegrityViolationException("Article has comments"))
                .when(articles).delete(article);

        mvc.perform(delete("/api/articles/1").session(session(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("文章仍有评论或收藏，无法删除"));
    }

    @Test
    void adminCanEditAndDeleteAnotherAuthorsArticle() throws Exception {
        Article article = article(); // 作者是 7，管理员是 9。
        when(users.findById(9L)).thenReturn(Optional.of(user(9L, "ADMIN")));
        when(articles.findById(1L)).thenReturn(Optional.of(article));
        when(articles.saveAndFlush(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockHttpSession session = session(9L);

        mvc.perform(put("/api/articles/1").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated title"));
        mvc.perform(delete("/api/articles/1").session(session))
                .andExpect(status().isNoContent());

        // 管理员修改内容，不会变成这篇文章的作者。
        assertEquals(7L, article.getAuthorId());
        assertEquals("Updated content", article.getContent());
        verify(articles).saveAndFlush(article);
        verify(articles).delete(article);
    }

    @Test
    void revokedAdminRoleTakesEffectOnTheNextRequest() throws Exception {
        Article article = article();
        // 同一个 Session：第一次查询是管理员，第二次已经被改为普通用户。
        when(users.findById(9L))
                .thenReturn(Optional.of(user(9L, "ADMIN")))
                .thenReturn(Optional.of(user(9L, "USER")));
        when(articles.findById(1L)).thenReturn(Optional.of(article));
        when(articles.saveAndFlush(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockHttpSession session = session(9L);

        mvc.perform(put("/api/articles/1").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/articles/1").session(session))
                .andExpect(status().isForbidden());

        verify(users, times(2)).findById(9L);
        verify(articles, never()).delete(any(Article.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void disabledUserCannotWriteWithAnOldSession(String roleCode) throws Exception {
        BlogUser disabledUser = user(7L, roleCode);
        disabledUser.setEnabled(false);
        when(users.findById(7L)).thenReturn(Optional.of(disabledUser));

        // 发布、编辑、删除分别带上旧会话，都必须在访问文章仓库前被拦截。
        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/articles").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY),
                put("/api/articles/1").contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY),
                delete("/api/articles/1"))) {
            MockHttpSession session = session(7L);
            mvc.perform(request.session(session))
                    .andExpect(status().isUnauthorized());
            assertTrue(session.isInvalid());
        }

        verifyNoInteractions(articles);
    }

    @Test
    void readingArticlesRemainsPublic() throws Exception {
        Article article = article();
        when(articles.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(article));
        when(articles.findById(1L)).thenReturn(Optional.of(article));

        mvc.perform(get("/api/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
        mvc.perform(get("/api/articles/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorId").value(7));

        verifyNoInteractions(users);
    }


    @Test
    void missingDetailKeepsAnEmptyNotFoundResponse() throws Exception {
        when(articles.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/articles/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        verifyNoInteractions(users);
    }

    @Test
    void categoryListUsesTheExistingCategoryQuery() throws Exception {
        when(articles.findByCategoryIdOrderByCreatedAt(1L)).thenReturn(List.of(article()));
        mvc.perform(get("/api/articles").param("categoryId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].categoryId").value(1));
        verify(articles).findByCategoryIdOrderByCreatedAt(1L);
        verifyNoMoreInteractions(articles);
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void paginationKeepsResponseFieldsAndStableSorting(boolean filtered) throws Exception {
        PageRequest expected = PageRequest.of(1, 5,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        PageImpl<Article> result = new PageImpl<>(List.of(article()), expected, 6);
        if (filtered) {
            when(articles.findByCategoryId(1L, expected)).thenReturn(result);
        } else {
            when(articles.findAll(expected)).thenReturn(result);
        }

        MockHttpServletRequestBuilder request = get("/api/articles/page")
                .param("page", "1").param("size", "5");
        if (filtered) request.param("categoryId", "1");
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @CsvSource({"-1,5", "0,0", "0,101"})
    void invalidPaginationIsRejectedBeforeQuerying(int page, int size) throws Exception {
        mvc.perform(get("/api/articles/page")
                        .param("page", String.valueOf(page)).param("size", String.valueOf(size)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("页码不能小于 0，每页数量必须在 1 到 100 之间"));
        verifyNoInteractions(articles, users);
    }

    @Test
    void extractedRequestRecordsStillValidateInput() throws Exception {
        mvc.perform(post("/api/articles").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":" ","content":"text","categoryId":1}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/articles").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"title","content":"text","categoryId":0}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/articles/1").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"title","content":" "}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(articles, users);
    }

    @Test
    void createConstraintFailureKeepsBadRequestMessage() throws Exception {
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "USER")));
        when(articles.saveAndFlush(any(Article.class)))
                .thenThrow(new DataIntegrityViolationException("Foreign key violation"));
        mvc.perform(post("/api/articles").session(session(7L))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("作者或分类不存在，或文章数据不符合数据库要求"));
    }

    @Test
    void deleteConstraintFailureDuringFlushKeepsConflictMessage() throws Exception {
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "USER")));
        Article article = article();
        when(articles.findById(1L)).thenReturn(Optional.of(article));
        doThrow(new DataIntegrityViolationException("Deferred foreign key violation"))
                .when(articles).flush();

        mvc.perform(delete("/api/articles/1").session(session(7L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("文章仍有评论或收藏，无法删除"));
        verify(articles).delete(article);
        verify(articles).flush();
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", userId);
        return session;
    }

    private Article article() {
        Article article = new Article();
        article.setId(1L);
        article.setAuthorId(7L);
        article.setCategoryId(1L);
        article.setTitle("Original title");
        article.setContent("Original content");
        return article;
    }
}
