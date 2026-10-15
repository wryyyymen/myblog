package com.syf.blog.auth;

import com.syf.blog.domain.BlogUser;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.repository.BlogUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 统一管理 Session 的身份读取、登录建立和退出；每次读取身份都查询数据库。 */
@Service
public class CurrentUserService {
    private static final String LOGIN_USER_ID = "loginUserId";
    private static final String LOGIN_REQUIRED = "请先登录或登录状态已失效";
    private final BlogUserRepository users;

    public CurrentUserService(BlogUserRepository users) {
        this.users = users;
    }

    /**
     * 文章、分类等业务接口获取有效用户。
     *
     * @param request 当前 HTTP 请求，只读取已有会话
     * @return 启用的登录用户，其角色来自数据库
     * @throws BusinessException 登录状态无效时返回 401 和统一提示
     */
    public BlogUser requireLogin(HttpServletRequest request) {
        return requireUser(request, false);
    }

    /**
     * 当前用户接口获取有效用户，并保留原有的具体失效提示。
     *
     * @param request 当前 HTTP 请求，只读取已有会话
     * @return 启用的登录用户
     * @throws BusinessException 未登录、用户不存在或账号停用时返回 401
     */
    public BlogUser requireProfileUser(HttpServletRequest request) {
        return requireUser(request, true);
    }

    /**
     * 在凭据校验成功后建立登录会话，并更换会话 ID。
     *
     * @param request 当前 HTTP 请求
     * @param userId UserService 验证成功后的用户 ID，不能直接使用客户端提供的 ID
     */
    public void startSession(HttpServletRequest request, Long userId) {
        HttpSession session = request.getSession();
        request.changeSessionId();
        session.setAttribute(LOGIN_USER_ID, userId);
    }

    /**
     * 退出已有会话，匿名调用时不创建新会话。
     *
     * @param request 当前 HTTP 请求
     */
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** 所有受保护接口共用同一套身份判断，仅提示文字按原接口约定区分。 */
    private BlogUser requireUser(HttpServletRequest request, boolean detailedMessage) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(LOGIN_USER_ID) instanceof Long userId)) {
            throw unauthorized(detailedMessage ? "请先登录" : LOGIN_REQUIRED);
        }

        BlogUser user = users.findById(userId).orElse(null);
        if (user == null) {
            session.invalidate();
            throw unauthorized(detailedMessage ? "登录用户不存在，请重新登录" : LOGIN_REQUIRED);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            session.invalidate();
            throw unauthorized(detailedMessage ? "账号已停用" : LOGIN_REQUIRED);
        }
        return user;
    }

    private BusinessException unauthorized(String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, message);
    }
}
