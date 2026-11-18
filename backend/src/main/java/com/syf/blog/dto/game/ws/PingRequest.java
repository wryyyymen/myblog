package com.syf.blog.dto.game.ws;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 发往 STOMP 目标 {@code /app/ping} 的连通性测试请求。
 *
 * <p>消息正文形如 {@code {"text":"hello"}}，由 Spring 转换为本对象；
 * Controller 参数上的 {@code @Valid} 触发字段约束校验。
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "STOMP连通性测试")
public class PingRequest {
    /**
     * 要原样返回的测试文本，不能为 null、空字符串或全空白，长度上限由下方的 @Size 声明。
     */
    @NotBlank(message = "text不能为空")
    @Size(max = 20000,message = "text最大长度为20000字符")
    @Schema(description = "测试文本",example = "hello")
    private String text;
}
