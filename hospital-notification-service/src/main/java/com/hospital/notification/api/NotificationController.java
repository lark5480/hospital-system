package com.hospital.notification.api;

import com.hospital.notification.model.NotificationRecord;
import com.hospital.notification.store.NotificationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationStore store;

    @GetMapping("/events")
    public Map<String, Object> events() {
        List<NotificationRecord> list = store.all();
        return Map.of(
                "total", list.size(),
                "items", list
        );
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "notification", "status", "up");
    }
}
