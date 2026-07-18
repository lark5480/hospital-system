package com.hospital.core.dispatch.infrastructure;

import com.hospital.core.dispatch.domain.PatientCalledEvent;
import com.hospital.core.platform.config.MessagingConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * RabbitMQ bridge for dispatch domain events.
 * Triggered after DispatchService transaction commits, publishes PatientCalledEvent
 * to hospital.exchange. Notification service consumes it downstream (decoupled, async).
 * 与 clinical 的 VisitEventAmqpBridge 同款模式(见 ADR-007 / ADR-010)。
 */
@Component
@RequiredArgsConstructor
public class PatientCalledEventAmqpBridge {

    private final AmqpTemplate amqpTemplate;

    @TransactionalEventListener
    public void onPatientCalled(PatientCalledEvent event) {
        amqpTemplate.convertAndSend(
                MessagingConfig.EXCHANGE,
                MessagingConfig.ROUTING_PATIENT_CALLED,
                event);
    }
}
