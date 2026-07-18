package com.hospital.notification.consumer;

import com.hospital.notification.config.MessagingConfig;
import com.hospital.notification.model.VisitCreatedEvent;
import com.hospital.notification.store.NotificationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumer for visit-created events. Triggers notification recording.
 * Received events are stored in NotificationStore for frontend polling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationStore store;

    @RabbitListener(queues = MessagingConfig.Q_NOTIFICATION)
    public void onVisitCreated(VisitCreatedEvent event) {
        log.info("[notification] visitCreated event received visitId={}, patientId={}, ready to send notification",
                event.visitId(), event.patientId());
        store.record(event);
    }
}