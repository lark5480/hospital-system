package com.hospital.notification.consumer;

import com.hospital.notification.config.MessagingConfig;
import com.hospital.notification.model.VisitStatusEvent;
import com.hospital.notification.store.NotificationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费就诊状态变更事件，记录通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitStatusEventConsumer {

    private final NotificationStore store;

    @RabbitListener(queues = MessagingConfig.Q_NOTIFICATION_VISIT_STATUS)
    public void onVisitStatusChanged(VisitStatusEvent event) {
        log.info("[Consumer] 就诊状态变更: visitId={}, status={}", event.visitId(), event.status());
        store.recordVisitStatus(event);
    }
}
