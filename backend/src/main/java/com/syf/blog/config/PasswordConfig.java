package com.syf.blog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 提供项目现有的 BCrypt 密码编码器，供业务服务通过构造方法注入。 */
@Configuration
public class PasswordConfig {

    /** @return 用于注册密码编码及登录密码校验的编码器 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
