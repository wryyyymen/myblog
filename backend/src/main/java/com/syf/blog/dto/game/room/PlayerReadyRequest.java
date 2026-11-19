package com.syf.blog.dto.game.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 准备和取消准备共用同一请求，身份从连接取得。 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "修改当前成员的准备状态")
public class PlayerReadyRequest {

    @NotBlank(message = "房间号不能为空")
    @Size(max = 36, message = "房间号最多允许36个字符")
    @Schema(description = "当前房间号，用于拒绝迟到的跨房间操作")
    private String roomId;

    // 使用 Boolean 而不是 boolean，才能区分 false 与请求漏传字段。
    @NotNull(message = "准备状态不能为空")
    @Schema(description = "true 表示准备，false 表示取消准备", example = "true")
    private Boolean ready;
}
