package com.hospital.core.clinical.domain;

import java.time.LocalDateTime;

/**
 * 领域事件:就诊已创建。经 RabbitMQ 桥接发给通知服务等下游。
 */
public record VisitCreatedEvent(Long visitId, Long patientId, Long doctorId, LocalDateTime createdAt) {
}
