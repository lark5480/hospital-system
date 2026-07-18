package com.hospital.core.booking.domain;

/**
 * 体检进度回写事件:Dispatch 模块在 ExamTask 推进时发布,Booking 模块监听并更新预约单状态。
 * 采用事件而非直接调用,保持模块间单向依赖(与 AppointmentCreatedEvent 同源设计),不破坏 ArchUnit 模块隔离。
 *
 * <p>status 取值:
 * <ul>
 *   <li>{@code CHECKED_IN} — 该预约首个任务开始检查(到院)</li>
 *   <li>{@code DONE} — 该预约全部任务完成</li>
 * </ul>
 */
public record AppointmentStatusEvent(Long appointmentId, String status) {
}
