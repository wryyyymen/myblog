package com.syf.blog.controller;

import com.syf.blog.domain.BlogUser;
import com.syf.blog.auth.CurrentUserService;
import com.syf.blog.exception.GlobalExceptionHandler;
import com.syf.blog.service.UserService;
import com.syf.blog.repository.BlogUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {
    @Mock
    private BlogUserRepository users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UserController(
                        new UserService(users, new BCryptPasswordEncoder()), new CurrentUserService(users)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void successfulLoginStoresIdentityAndChangesSessionId(String roleCode) throws Exception {
        BlogUser user = user();
        user.setRoleCode(roleCode);
        when(users.findByUsername("owner")).thenReturn(Optional.of(user));
        MockHttpSession session = new MockHttpSession();
        String oldSessionId = session.getId();

        mvc.perform(post("/api/auth/login").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"username":"owner","password":"test-password-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(request().sessionAttribute("loginUserId", 7L))
                .andExpect(jsonPath("$.username").value("owner"))
                .andExpect(jsonPath("$.role").value(roleCode))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        assertNotEquals(oldSessionId, session.getId());
    }

    @Test
    void wrongPasswordDoesNotCreateAuthenticatedSession() throws Exception {
        when(users.findByUsername("owner")).thenReturn(Optional.of(user()));

        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"username":"owner","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void logoutInvalidatesSessionAndCurrentUserBecomesUnauthorized() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", 7L);

        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertTrue(session.isInvalid());
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @Test
    void logoutWithoutSessionAlsoSucceeds() throws Exception {
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void currentUserResponseContainsPublicUserInformation(String roleCode) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", 7L);
        BlogUser user = user();
        user.setRoleCode(roleCode);
        when(users.findById(7L)).thenReturn(Optional.of(user));

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.username").value("owner"))
                .andExpect(jsonPath("$.role").value(roleCode))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void disabledUserCannotLogInEvenWithTheCorrectPassword(String roleCode) throws Exception {
        BlogUser user = user();
        user.setRoleCode(roleCode);
        user.setEnabled(false);
        when(users.findByUsername("owner")).thenReturn(Optional.of(user));

        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"username":"owner","password":"test-password-123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("账号已停用"))
                .andReturn();

        assertNull(result.getRequest().getSession(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void currentUserRejectsDisabledAccountsAndInvalidatesTheirSession(String roleCode)
            throws Exception {
        BlogUser user = user();
        user.setRoleCode(roleCode);
        user.setEnabled(false);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", 7L);

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号已停用"));

        assertTrue(session.isInvalid());
    }

    @Test
    void registrationCannotGrantAnAdminRoleFromTheRequest() throws Exception {
        when(users.saveAndFlush(any(BlogUser.class))).thenAnswer(invocation -> {
            BlogUser user = invocation.getArgument(0);
            user.setId(8L);
            return user;
        });

        // 客户端即使多传管理员字段，服务端创建的用户也必须是普通用户。
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"username":"new-user","password":"test-password-123",
                                 "role":"ADMIN","roleCode":"ADMIN"}
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<BlogUser> saved = ArgumentCaptor.forClass(BlogUser.class);
        verify(users).saveAndFlush(saved.capture());
        assertEquals("USER", saved.getValue().getRoleCode());
        assertEquals(Boolean.TRUE, saved.getValue().getEnabled());
        assertTrue(new BCryptPasswordEncoder().matches(
                "test-password-123", saved.getValue().getPasswordHash()));
    }


    @Test
    void duplicateUsernameUsesTheSharedBusinessErrorResponse() throws Exception {
        when(users.existsByUsername("owner")).thenReturn(true);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":" owner ","password":"test-password-123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("用户名已经存在"));
        verify(users, never()).saveAndFlush(any(BlogUser.class));
    }

    @Test
    void unknownUserCannotCreateASession() throws Exception {
        when(users.findByUsername("missing")).thenReturn(Optional.empty());
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"username":"missing","password":"test-password-123"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                .andReturn();
        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void deletedCurrentUserInvalidatesSessionAndKeepsItsMessage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", 7L);
        when(users.findById(7L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("登录用户不存在，请重新登录"));
        assertTrue(session.isInvalid());
    }

    @Test
    void absentOrMalformedIdentityReturnsUnauthorized() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", "7");
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @Test
    void extractedRequestsStillRejectInvalidInput() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"reader","password":"short"}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":" ","password":"test-password-123"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    @Test
    void registrationReturnsOnlyPublicFieldsAndDoesNotLogIn() throws Exception {
        when(users.saveAndFlush(any(BlogUser.class))).thenAnswer(invocation -> {
            BlogUser user = invocation.getArgument(0);
            user.setId(8L);
            return user;
        });
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"username":" new-user ","password":"test-password-123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"id":8,"username":"new-user"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andReturn();
        assertNull(result.getRequest().getSession(false));
    }

    private BlogUser user() {
        BlogUser user = new BlogUser("owner",
                new BCryptPasswordEncoder().encode("test-password-123"));
        user.setId(7L);
        return user;
    }
}
