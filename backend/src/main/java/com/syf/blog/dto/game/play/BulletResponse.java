package com.syf.blog.dto.game.play;

import com.syf.blog.game.runtime.BulletState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "服务端计算的子弹位置")
public class BulletResponse {
    private final long id;
    private final Long ownerUserId;
    private final double x;
    private final double y;
    private final int direction;

    public static BulletResponse from(BulletState bullet) {
        BulletResponse response = new BulletResponse(bullet.getId(), bullet.getOwnerUserId(),
                bullet.getX(), bullet.getY(), bullet.getDirection());
        return response;
    }
}
