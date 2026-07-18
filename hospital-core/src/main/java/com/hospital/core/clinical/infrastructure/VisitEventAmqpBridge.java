package com.hospital.core.clinical.infrastructure;

import com.hospital.core.clinical.domain.VisitCreatedEvent;
import com.hospital.core.platform.config.MessagingConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * RabbitMQ bridge for domain events.
 * Triggered after VisitService transaction commits, publishes event to hospital.exchange.
 * Notification service consumes it downstream (decoupled, async, scalable).
 */
@Component
@RequiredArgsConstructor
public class VisitEventAmqpBridge {

    private final AmqpTemplate amqpTemplate;

    @TransactionalEventListener
    public void onVisitCreated(VisitCreatedEvent event) {
        amqpTemplate.convertAndSend(
                MessagingConfig.EXCHANGE,
                MessagingConfig.ROUTING_VISIT_CREATED,
                event);
    }
}