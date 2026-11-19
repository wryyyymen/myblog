package com.syf.blog.game.runtime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * 服务端保存的最新方向输入。
 * Lombok 构造参数依次为 seq、direction、receivedAtMillis。
 */
@Getter
@RequiredArgsConstructor
public class PlayerInput {

    private final long seq;

    // 0 上、1 下、2 左、3 右，null 表示停止。
    private final Integer direction;

    // 由服务端接收消息时记录，不能由客户端决定输入有效期。
    private final long receivedAtMillis;

    // 保留原三参数构造方法；业务服务创建输入后，单独设置持续开火状态。
    @Setter
    private boolean fireHeld;
}
