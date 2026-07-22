package com.hospital.notification.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hospital.notification.model.NotificationBroadcastEvent;
import com.hospital.notification.model.NotificationRecord;
import com.hospital.notification.store.NotificationStore;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationStore store;
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    @GetMapping("/events")
    public Map<String, Object> events() {
        List<NotificationRecord> list = store.all();
        return Map.of(
                "total", list.size(),
                "items", list
        );
    }

    @GetMapping("/events/by-role")
    public Map<String, Object> eventsByRole(@RequestParam String role) {
        List<NotificationRecord> list = store.findByRole(role);
        return Map.of(
                "total", list.size(),
                "items", list
        );
    }

    @GetMapping("/events/by-dept")
    public Map<String, Object> eventsByDept(@RequestParam Long deptId) {
        List<NotificationRecord> list = store.findByDeptId(deptId);
        return Map.of(
                "total", list.size(),
                "items", list
        );
    }

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    @EventListener
    public void onNotificationBroadcast(NotificationBroadcastEvent event) {
        broadcast(event.record());
    }

    private void broadcast(NotificationRecord record) {
        if (emitters.isEmpty()) return;
        Set<SseEmitter> dead = new CopyOnWriteArraySet<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("notification")
                        .data(record, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "notification", "status", "up");
    }
}
