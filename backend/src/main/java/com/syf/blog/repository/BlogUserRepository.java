package com.syf.blog.repository;

import com.syf.blog.domain.BlogUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户数据访问接口，由 Spring Data JPA 自动生成实现。
 *
 * BlogUserRepository 负责操作 BlogUser 对应的数据库表。
 *
 * 继承 {@code JpaRepository<BlogUser, Long>} 后，
 * Spring Data JPA 会自动帮我们提供常用的增删改查方法。
 *
 * BlogUser：表示要操作的实体类
 * Long：表示 BlogUser 主键 id 的类型
 */
public interface BlogUserRepository extends JpaRepository<BlogUser,Long>
{
    /**
     * 根据用户名字段查询用户，不在此方法中执行密码或启用状态校验。
     *
     * <p>用户名去除两端空白、密码匹配以及账号启用检查由调用方处理。
     *
     * @param username 要匹配的用户名，调用方应先完成业务需要的规范化处理
     * @return 匹配用户；没有该用户名时返回空 {@link Optional}
     */
    Optional<BlogUser> findByUsername(String username);
    /**
     * 判断指定用户名是否已经存在，供注册业务进行重名检查。
     *
     * @param username 要检查的用户名，调用方应先完成业务需要的规范化处理
     * @return 已有该用户名时为 true，没有时为 false
     */
    boolean existsByUsername(String username);
}
