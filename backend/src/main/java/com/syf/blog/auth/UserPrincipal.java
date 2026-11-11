package com.syf.blog.auth;

import java.security.Principal;

/**
 * 保存当前 WebSocket 连接对应的用户身份。
 */
public class UserPrincipal implements Principal {

    // 当前登录用户的数据库 ID
    // final 表示这个字段在构造完成后不能重新赋值
    private final Long userId;

    /**
     * 创建一个用户身份对象。
     *
     * @param userId 握手阶段已经验证过的用户 ID
     */
    public UserPrincipal(Long userId) {
        this.userId = userId;

        System.out.println(
                "[UserPrincipal 构造方法] 保存 userId = " + this.userId);
    }

    /**
     * 按照 Principal 接口的要求，返回身份名称。
     * 当前项目使用用户 ID 字符串作为身份名称。
     */
    @Override
    public String getName() {
        String userIdText = this.userId.toString();

        System.out.println(
                "[UserPrincipal.getName] 返回身份名称 = " + userIdText);

        return userIdText;
    }
}