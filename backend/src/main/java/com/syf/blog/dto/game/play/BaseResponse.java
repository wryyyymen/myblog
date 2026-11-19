package com.syf.blog.dto.game.play;

import com.syf.blog.game.runtime.BaseState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "合作模式需要保护的基地")
public class BaseResponse {
    private final double x;
    private final double y;
    private final double size;
    private final int hp;
    private final boolean alive;

    public static BaseResponse from(BaseState base) {
        if (base == null) return null;
        BaseResponse response = new BaseResponse(base.getX(), base.getY(), base.getSize(), base.getHp(), base.getHp() > 0);
        return response;
    }
}
