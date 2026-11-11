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

/**
 * 用户业务服务，负责注册、密码校验和账号登录资格检查。
 *
 * <p>返回对象仅包含允许公开的用户字段。查询默认使用只读事务，
 * 注册方法单独开启写事务；会话的建立、读取和注销集中在 auth 包。
 */
@Service
@Transactional(readOnly = true)
public class UserService {
    private final BlogUserRepository users;
    private final PasswordEncoder encoder;

    /**
     * 创建用户服务，由 Spring 注入用户数据访问和密码编码组件。
     *
     * @param users 用户 Repository，用于检查重名、查询账号和保存注册数据
     * @param encoder 密码编码器，负责注册时编码密码、登录时验证密码与哈希是否匹配
     */
    public UserService(BlogUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /**
     * 在写事务中注册默认启用、角色为 USER 的普通用户。
     *
     * <p>用户名去掉两端空白后检查是否已存在；密码编码后再保存，
     * 使用 saveAndFlush 立即刷新数据库变更。注册成功后由调用方决定后续页面流程。
     *
     * @param request 已通过接口参数校验的用户名和明文密码
     * @return 新用户的 ID 和用户名，不包含明文密码或密码哈希
     * @throws BusinessException 重名查询发现用户名已存在时抛出，携带 409 状态
     * @throws org.springframework.dao.DataIntegrityViolationException 保存时触发唯一性等数据库约束冲突时抛出
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
     * 验证登录用户名、密码和账号启用状态。
     *
     * <p>按去掉两端空白后的用户名查询账号，使用密码编码器验证密码。
     * 校验成功后返回公开用户信息，调用方可据此交给 auth 包建立登录会话。
     *
     * @param request 已通过接口参数校验的用户名和明文密码
     * @return 登录用户的公开信息；角色来自数据库
     * @throws BusinessException 用户不存在或密码不匹配时携带 401，账号未启用时携带 403
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
