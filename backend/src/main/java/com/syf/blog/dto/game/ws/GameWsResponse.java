package com.syf.blog.dto.game.ws;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;


/**
 * 后端发送给前端的 WebSocket 或 STOMP 消息正文。
 *
 * <p>type 供前端区分消息用途，payload 保存这条消息的业务数据。
 * Lombok 的 {@code @Getter} 生成读取字段的方法，
 * {@code @AllArgsConstructor} 生成接收 type 和 payload 的构造方法。
 *
 * @param <T> payload 的数据类型，例如测试文本使用 String，其他消息可使用具体响应 DTO
 */

@Getter
@AllArgsConstructor
@Schema(description = "游戏 WebSocket 响应消息")
public class GameWsResponse<T> {
 /** 消息用途标识，例如 connection:ready、pong 或 error。 */
 @Schema(description = "消息类型",example = "connection:ready")
 private final String type;

 /** 与消息用途对应的业务数据，实际结构由泛型 T 决定。 */
 @Schema(description = "携带消息的具体数据")
 private final T payload;

}
