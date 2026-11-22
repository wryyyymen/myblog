package com.syf.blog.controller;

import com.syf.blog.dto.game.play.GameLoadedRequest;
import com.syf.blog.dto.game.play.PlayerInputRequest;
import com.syf.blog.dto.game.play.SyncGameRequest;
import com.syf.blog.dto.game.room.RoomResponse;
import com.syf.blog.dto.game.room.StartGameRequest;
import com.syf.blog.dto.game.room.RematchGameRequest;
import com.syf.blog.dto.game.ws.GameWsResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.service.game.GameMatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import java.security.Principal;

/** 游戏操作入口。Lombok 生成只接收 GameMatchService 的构造方法。 */
@Controller
@RequiredArgsConstructor
public class GamePlayController {
    private final GameMatchService gameMatchService;

    @MessageMapping("/room/start")
    @SendToUser(value = "/queue/replies", broadcast = false)
    public GameWsResponse<RoomResponse> start(@Valid @Payload StartGameRequest request, Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        RoomResponse room = gameMatchService.start(userId, request.getRoomId(), request.getLevelId());
        GameWsResponse<RoomResponse> response = new GameWsResponse<RoomResponse>("room:started", room);
        return response;
    }

    @MessageMapping("/game/loaded")
    public void loaded(@Valid @Payload GameLoadedRequest request, Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        gameMatchService.loaded(userId, request.getRoomId(), request.getMatchId());
    }

    @MessageMapping("/player/input")
    public void input(@Valid @Payload PlayerInputRequest request, Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        gameMatchService.input(userId, request.getRoomId(), request.getMatchId(), request.getSeq(),
                request.getDirection(), request.isFireHeld());
    }

    @MessageMapping("/room/rematch")
    @SendToUser(value = "/queue/replies", broadcast = false)
    public GameWsResponse<RoomResponse> rematch(@Valid @Payload RematchGameRequest request, Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        RoomResponse room = gameMatchService.rematch(userId, request.getRoomId());
        GameWsResponse<RoomResponse> response = new GameWsResponse<RoomResponse>("room:rematched", room);
        return response;
    }

    @MessageMapping("/game/sync")
    public void sync(@Valid @Payload SyncGameRequest request, Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        gameMatchService.sync(userId, request.getRoomId());
    }

    @MessageExceptionHandler(BusinessException.class)
    @SendToUser(value = "/queue/replies", broadcast = false)
    public GameWsResponse<String> handleBusinessException(BusinessException exception) {
        GameWsResponse<String> response = new GameWsResponse<String>("error", exception.getMessage());
        return response;
    }

    @MessageExceptionHandler({MethodArgumentNotValidException.class, MessageConversionException.class})
    @SendToUser(value = "/queue/replies", broadcast = false)
    public GameWsResponse<String> handleInvalidMessage() {
        GameWsResponse<String> response = new GameWsResponse<String>("error", "游戏消息格式或参数不正确");
        return response;
    }
}
