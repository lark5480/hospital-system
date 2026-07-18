package com.hospital.core.dispatch.domain;

import java.time.LocalDateTime;

/**
 * 领域事件:患者被叫号(开始在某工位检查)。
 * 自包含快照,携带患者与工位信息,经 AMQP 发给通知服务,驱动 C 端「叫号通知」,
 * 与 AppointmentCreatedEvent / VisitCreatedEvent 一致采用防腐层 + 快照思路(见 ADR-007 / ADR-010)。
 */
public record PatientCalledEvent(
        Long taskId,
        Long appointmentId,
        Long patientId,
        String patientName,
        String station,
        String itemName,
        LocalDateTime calledAt
) {
}
