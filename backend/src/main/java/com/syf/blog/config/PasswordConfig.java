package com.syf.blog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 提供项目现有的 BCrypt 密码编码器，供业务服务通过构造方法注入。 */
@Configuration
public class PasswordConfig {

    /**
     * 创建 BCrypt 密码编码器，供用户业务服务注入使用。
     *
     * <p>注册时调用 encode 保存密码哈希，登录时调用 matches 比较明文与已有哈希。
     *
     * @return 用于注册密码编码及登录密码校验的编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
