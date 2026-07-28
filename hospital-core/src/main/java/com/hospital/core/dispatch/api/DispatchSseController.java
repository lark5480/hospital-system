package com.hospital.core.dispatch.api;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hospital.core.dispatch.application.BoardUpdateEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 排队看板SSE推送端点。
 * 前端订阅后，看板数据变更时实时推送，无需轮询。
 */
@Tag(name = "分诊排队", description = "排队看板SSE实时推送")
@Slf4j
@RestController
@RequestMapping("/api/core/dispatch/sse")
public class DispatchSseController {

    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    @Operation(summary = "订阅看板数据变更推送")
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@Parameter(description = "工位名称，可选，按工位过滤推送") @RequestParam(required = false) String station) {
        SseEmitter emitter = new SseEmitter(0L); // 无超时
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        
        emitters.add(emitter);
        log.debug("[SSE] 新增订阅者, 当前订阅数: {}", emitters.size());
        
        return emitter;
    }

    /**
     * 推送看板更新事件给所有订阅者。
     * 由 DispatchService 在业务操作后调用。
     */
    public void broadcastBoardUpdate(BoardUpdateEvent event) {
        if (emitters.isEmpty()) return;
        
        Map<String, Object> data = Map.of(
            "type", "board-update",
            "station", event.station(),
            "taskId", event.taskId(),
            "action", event.action()
        );
        
        Set<SseEmitter> deadEmitters = new CopyOnWriteArraySet<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("board-update")
                    .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }
}
