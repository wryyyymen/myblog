package com.syf.blog.dto.game.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 按 type 区分指令的通用 WebSocket 请求结构，供原生文本消息处理示例使用。
 *
 * <p>消息包含 type 和 payload；手工进行 JSON 转换后，调用方还需要执行参数校验。
 * 当前 STOMP 的 {@code /app/ping} 方法使用具体的 {@link PingRequest}，
 * 由消息目标决定调用哪个处理方法。
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "游戏 WebSocket 请求消息")
public class GameWsRequest {

    /** 指令类型，不能为 null、空字符串或全空白，例如 ping、room:join。 */
    @NotBlank(message = "消息类型不能为空")
    @Schema(description = "指令类型", example = "ping")
    private String type;

    /**
     * 指令携带的 JSON 数据，具体字段由对应指令决定，可以传空对象 {}。
     *
     * <p>{@code @NotNull} 检查 Java 引用是否为空；显式 JSON null 还可能表示为
     * NullNode，调用方需要进一步检查 {@code isNull()}。
     * 内部业务字段需要由具体指令的处理逻辑另行解析和校验。
     */
    @NotNull(message = "消息数据不能为空")
    @Schema(description = "指令携带的数据", example = "{}")
    private JsonNode payload;
}
