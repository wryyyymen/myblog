package com.syf.blog.game.runtime;

import com.syf.blog.dto.game.ws.GameWsResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import java.util.List;

/** 在业务锁内生成快照，释放锁后再由发送组件投递。构造参数为接收者、消息。 */
@Getter
@RequiredArgsConstructor
public class GameBroadcast {
    private final List<Long> recipientUserIds;
    private final GameWsResponse<?> message;
}
