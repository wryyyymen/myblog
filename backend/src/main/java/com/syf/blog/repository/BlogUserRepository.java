package com.syf.blog.repository;

import com.syf.blog.domain.BlogUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户数据访问层
 *
 * BlogUserRepository 负责操作 BlogUser 对应的数据库表。
 *
 * 继承 JpaRepository<BlogUser, Long> 后，
 * Spring Data JPA 会自动帮我们提供常用的增删改查方法。
 *
 * BlogUser：表示要操作的实体类
 * Long：表示 BlogUser 主键 id 的类型
 */
public interface BlogUserRepository extends JpaRepository<BlogUser,Long>
{
    /**
     * 根据用户名查询用户
     *
     * Spring Data JPA 会根据方法名 findByUsername
     * 自动生成对应的查询逻辑。
     * 类似 SQL：
     * SELECT * FROM users WHERE username = ?
     *
     * Optional<BlogUser> 表示：
     * 可能查询到用户，也可能查询不到。
     */
    Optional<BlogUser> findByUsername(String username);
    /**
     * 判断指定用户名是否已经存在
     *
     * 如果存在，返回 true
     * 如果不存在，返回 false
     *
     * 用于注册时判断用户名是否已经被占用。
     */
    boolean existsByUsername(String username);
}
