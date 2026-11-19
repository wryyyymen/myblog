package com.syf.blog.game.runtime;

import com.syf.blog.dto.game.room.RoomLeftResponse;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.dto.game.play.GameEndResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 退出业务的结果，供 Controller 分别通知退出者与剩余成员。
 * Lombok 构造参数依次为 leftResponse、remainingRoomResponse、endedGame。
 */
@Getter
@RequiredArgsConstructor
public class RoomLeaveResult {

    private final RoomLeftResponse leftResponse;

    // 房间为空被删除时为 null，此时没有剩余成员需要通知。
    private final RoomResponse remainingRoomResponse;

    // 等待房间退出时没有对局；加载或运行期间退出时携带结束快照。
    private final GameEndResponse endedGame;
}
