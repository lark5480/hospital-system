package com.hospital.core.booking.domain;

import java.util.List;

/**
 * 领域事件:体检预约已创建。
 * 采用「自包含快照」——直接携带患者名与各项目简报,下游 Dispatch 模块
 * 消费时无需再同步回查 booking / patient,做到零跨模块 DAO 依赖。
 * 这样未来把 Dispatch 抽为独立服务时,只需消费该事件即可(事件驱动 + 防腐层),
 * 与 ADR-007 / ADR-010 的 strangler fig 演进路线一致。
 */
public record AppointmentCreatedEvent(
        Long appointmentId,
        Long patientId,
        String patientName,
        Long packageId,
        List<ExamItemBrief> items
) {
}
