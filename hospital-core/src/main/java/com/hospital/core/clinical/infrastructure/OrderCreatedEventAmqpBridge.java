package com.hospital.core.clinical.infrastructure;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hospital.core.clinical.domain.OrderCreatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 医嘱创建事件 → RabbitMQ 桥接。
 * 事务提交后才发送到 RabbitMQ，保证与数据库事务一致性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedEventAmqpBridge {

    private final RabbitTemplate rabbitTemplate;
    private final TopicExchange exchange;

    private static final String ROUTING_KEY = "order.created";

    @TransactionalEventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("[AMQP] 发送医嘱创建事件: visitId={}, type={}, target={}",
                event.visitId(), event.orderType(), event.targetRole());
        rabbitTemplate.convertAndSend(exchange.getName(), ROUTING_KEY, event);
    }
}
