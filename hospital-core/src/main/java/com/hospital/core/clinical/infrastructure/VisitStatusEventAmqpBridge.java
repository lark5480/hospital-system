package com.hospital.core.clinical.infrastructure;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hospital.core.clinical.domain.VisitStatusEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 就诊状态变更事件 → RabbitMQ 桥接。
 * 事务提交后才发送到 RabbitMQ，保证与数据库事务一致性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitStatusEventAmqpBridge {

    private final RabbitTemplate rabbitTemplate;
    private final TopicExchange exchange;

    private static final String ROUTING_KEY = "visit.status";

    @TransactionalEventListener
    public void onVisitStatusChanged(VisitStatusEvent event) {
        log.info("[AMQP] 发送就诊状态变更事件: visitId={}, status={}", event.visitId(), event.status());
        rabbitTemplate.convertAndSend(exchange.getName(), ROUTING_KEY, event);
    }
}
