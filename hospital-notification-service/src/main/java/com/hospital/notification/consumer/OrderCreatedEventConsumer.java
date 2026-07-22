package com.hospital.notification.consumer;

import com.hospital.notification.config.MessagingConfig;
import com.hospital.notification.model.OrderCreatedEvent;
import com.hospital.notification.store.NotificationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费医嘱创建事件，记录通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedEventConsumer {

    private final NotificationStore store;

    @RabbitListener(queues = MessagingConfig.Q_NOTIFICATION_ORDER_CREATED)
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("[Consumer] 医嘱创建: visitId={}, type={}, target={}", 
                event.visitId(), event.orderType(), event.targetRole());
        store.recordOrderCreated(event);
    }
}
