package com.syf.blog.controller;

import com.syf.blog.domain.BlogUser;
import com.syf.blog.auth.CurrentUserService;
import com.syf.blog.exception.GlobalExceptionHandler;
import com.syf.blog.domain.Category;
import com.syf.blog.service.CategoryService;
import com.syf.blog.repository.BlogUserRepository;
import com.syf.blog.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 分类公开查询和管理员查询的权限测试，不连接真实数据库。 */
@ExtendWith(MockitoExtension.class)
class CategoryControllerTest {
    @Mock
    private CategoryRepository categories;
    @Mock
    private BlogUserRepository users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new CategoryController(new CategoryService(categories), new CurrentUserService(users)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void publicListIsAvailableWithoutLogin() throws Exception {
        when(categories.findByEnabledTrueOrderByNameAsc()).thenReturn(List.of(category(1L, true)));

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].enabled").value(true));

        verify(categories).findByEnabledTrueOrderByNameAsc();
        verifyNoMoreInteractions(categories);
        verifyNoInteractions(users);
    }

    @Test
    void anonymousRequestIsRejectedWithoutCreatingSession() throws Exception {
        var result = mvc.perform(get("/api/categories/admin"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertNull(result.getRequest().getSession(false));
        verifyNoInteractions(categories, users);
    }

    @Test
    void missingOrMalformedSessionUserIdIsRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/api/categories/admin").session(session))
                .andExpect(status().isUnauthorized());

        session.setAttribute("loginUserId", "9");
        mvc.perform(get("/api/categories/admin").session(session))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(categories, users);
    }

    @Test
    void ordinaryUserCannotClaimAdminRole() throws Exception {
        BlogUser user = user("USER");
        user.setUsername("demo_admin");
        when(users.findById(9L)).thenReturn(Optional.of(user));

        mvc.perform(get("/api/categories/admin").session(session())
                        .param("role", "ADMIN").param("roleCode", "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("只有管理员可以管理分类"));

        verifyNoInteractions(categories);
    }

    @Test
    void adminCanSeeEnabledAndDisabledCategories() throws Exception {
        when(users.findById(9L)).thenReturn(Optional.of(user("ADMIN")));
        when(categories.findAllByOrderByNameAsc())
                .thenReturn(List.of(category(1L, true), category(2L, false)));

        mvc.perform(get("/api/categories/admin").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].enabled").value(false));

        verify(categories).findAllByOrderByNameAsc();
        verifyNoMoreInteractions(categories);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void disabledAccountIsRejectedAndSessionInvalidated(String role) throws Exception {
        BlogUser user = user(role);
        user.setEnabled(false);
        when(users.findById(9L)).thenReturn(Optional.of(user));
        MockHttpSession session = session();

        mvc.perform(get("/api/categories/admin").session(session))
                .andExpect(status().isUnauthorized());

        assertTrue(session.isInvalid());
        verifyNoInteractions(categories);
    }

    @Test
    void deletedAccountIsRejectedAndSessionInvalidated() throws Exception {
        when(users.findById(9L)).thenReturn(Optional.empty());
        MockHttpSession session = session();

        mvc.perform(get("/api/categories/admin").session(session))
                .andExpect(status().isUnauthorized());

        assertTrue(session.isInvalid());
        verifyNoInteractions(categories);
    }

    @Test
    void revokedAdminRoleTakesEffectOnNextRequest() throws Exception {
        when(users.findById(9L))
                .thenReturn(Optional.of(user("ADMIN")))
                .thenReturn(Optional.of(user("USER")));
        when(categories.findAllByOrderByNameAsc()).thenReturn(List.of());
        MockHttpSession session = session();

        mvc.perform(get("/api/categories/admin").session(session))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get("/api/categories/admin").session(session))
                .andExpect(status().isForbidden());

        verify(users, times(2)).findById(9L);
        verify(categories, times(1)).findAllByOrderByNameAsc();
        verifyNoMoreInteractions(categories);
    }


    @Test
    void categoryResponsePreservesAllExistingFields() throws Exception {
        Category category = category(1L, true);
        category.setDescription("Category description");
        category.setCreatedAt(java.time.LocalDateTime.of(2026, 10, 1, 12, 0));
        category.setUpdatedAt(java.time.LocalDateTime.of(2026, 10, 2, 12, 0));
        when(categories.findByEnabledTrueOrderByNameAsc()).thenReturn(List.of(category));

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Category 1"))
                .andExpect(jsonPath("$[0].description").value("Category description"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
    }

    @Test
    void serviceItselfRejectsUnauthorizedCallers() {
        CategoryService service = new CategoryService(categories);
        var forbidden = assertThrows(com.syf.blog.exception.BusinessException.class,
                () -> service.listAll(user("USER")));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, forbidden.getStatus());

        BlogUser disabled = user("ADMIN");
        disabled.setEnabled(false);
        for (BlogUser invalid : java.util.Arrays.asList(null, disabled)) {
            var unauthorized = assertThrows(com.syf.blog.exception.BusinessException.class,
                    () -> service.listAll(invalid));
            assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, unauthorized.getStatus());
        }
        verifyNoInteractions(categories);
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", 9L);
        return session;
    }

    private BlogUser user(String role) {
        BlogUser user = new BlogUser();
        user.setId(9L);
        user.setRoleCode(role);
        user.setEnabled(true);
        return user;
    }

    private Category category(Long id, boolean enabled) {
        Category category = new Category();
        category.setId(id);
        category.setName("Category " + id);
        category.setEnabled(enabled);
        return category;
    }
}
