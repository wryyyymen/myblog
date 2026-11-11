package com.syf.blog.robot;

import com.syf.blog.auth.CurrentUserService;
import com.syf.blog.domain.BlogUser;
import com.syf.blog.exception.GlobalExceptionHandler;
import com.syf.blog.repository.BlogUserRepository;
import com.syf.blog.robot.controller.RobotEventController;
import com.syf.blog.robot.service.RobotEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RobotEventControllerTest {
    private BlogUserRepository users;
    private RobotEventService events;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(BlogUserRepository.class);
        events = new RobotEventService();
        mvc = MockMvcBuilders.standaloneSetup(
                        new RobotEventController(events, new CurrentUserService(users)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    void anonymousRequestsCannotReadLatestOrSubscribe() throws Exception {
        var latest = mvc.perform(get("/api/robot/results/latest"))
                .andExpect(status().isUnauthorized()).andReturn();
        var stream = mvc.perform(get("/api/robot/events"))
                .andExpect(status().isUnauthorized()).andReturn();

        assertNull(latest.getRequest().getSession(false));
        assertNull(stream.getRequest().getSession(false));
        verifyNoInteractions(users);
    }

    @Test
    void loggedInUserCanReadLatestAndOpenEventStream() throws Exception {
        BlogUser user = new BlogUser();
        user.setId(7L);
        user.setEnabled(true);
        user.setRoleCode("USER");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", 7L);

        mvc.perform(get("/api/robot/results/latest").session(session))
                .andExpect(status().isNoContent());
        events.publish("你好", 2, 97);
        mvc.perform(get("/api/robot/results/latest").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("你好"))
                .andExpect(jsonPath("$.commandId").value(2));
        mvc.perform(get("/api/robot/events").session(session))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
        mvc.perform(get("/api/robot/events").session(session)
                        .header("Last-Event-ID", "another-process:1"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }
}
