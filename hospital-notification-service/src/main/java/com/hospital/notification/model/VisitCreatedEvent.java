package com.hospital.notification.model;

import java.time.LocalDateTime;

/**
 * 与 hospital-core 发出的 VisitCreatedEvent 结构一致的本地反序列化模型,
 * 经 RabbitMQ JSON 消息传递,不依赖 core 模块(防腐层思路:下游自有模型)。
 */
public record VisitCreatedEvent(Long visitId, Long patientId, Long doctorId, LocalDateTime createdAt) {
}
