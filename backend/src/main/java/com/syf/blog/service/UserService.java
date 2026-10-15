package com.syf.blog.service;

import com.syf.blog.domain.BlogUser;
import com.syf.blog.dto.user.LoginRequest;
import com.syf.blog.dto.user.RegisterRequest;
import com.syf.blog.dto.user.RegisterResponse;
import com.syf.blog.dto.user.UserResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.repository.BlogUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户业务：注册、密码校验及登录资格检查。会话操作由 auth 包负责。 */
@Service
@Transactional(readOnly = true)
public class UserService {
    private final BlogUserRepository users;
    private final PasswordEncoder encoder;

    public UserService(BlogUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /**
     * 注册默认启用的普通用户，密码编码后保存。
     *
     * @param request 已通过参数校验的用户名和密码
     * @return 用户 ID 和用户名，不包含密码或密码哈希
     * @throws BusinessException 用户名已存在时返回 409
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String username = request.username().trim();
        if (users.existsByUsername(username)) {
            throw new BusinessException(HttpStatus.CONFLICT, "用户名已经存在");
        }

        BlogUser saved = users.saveAndFlush(
                new BlogUser(username, encoder.encode(request.password())));
        return new RegisterResponse(saved.getId(), saved.getUsername());
    }

    /**
     * 校验登录凭据及账号状态，不在此方法中建立 HTTP 会话。
     *
     * @param request 已通过参数校验的用户名和密码
     * @return 登录用户的公开信息；角色来自数据库
     * @throws BusinessException 凭据错误为 401，账号停用为 403
     */
    public UserResponse authenticate(LoginRequest request) {
        BlogUser user = users.findByUsername(request.username().trim()).orElse(null);
        if (user == null || !encoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "账号已停用");
        }
        return UserResponse.from(user);
    }
}
