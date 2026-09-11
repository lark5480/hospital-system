package com.hospital.core.booking.domain;

/**
 * 领域事件:体检预约已被患者自助取消。
 *
 * <p><b>为什么必须发这个事件(而不是只把预约状态改成 CANCELLED)</b>:
 * {@code BookingService.book()} 成功后会发布 {@link AppointmentCreatedEvent},
 * 由 Dispatch 模块的 {@code DispatchService.onAppointmentCreated} 立即为该预约的
 * 每个项目生成 {@code ExamTask} 并写入 {@code queue_board} 看板投影。
 * 也就是说「预约」在 booking 侧是一行,在 dispatch 侧却已经展开成 N 条排队任务。
 * 若取消时只改 booking 的状态,患者仍会留在各科室的排队队列里、大屏看板照样显示他 ——
 * 这是实打实的数据不一致。因此取消必须联动清理 dispatch 侧的任务与投影。
 *
 * <p><b>为什么走事件而不是直接调用</b>:模块间是事件驱动 + 单向依赖
 * ({@code booking.domain} 是 {@code @NamedInterface("domain")},dispatch 单向消费 booking 的事件,
 * 有 ArchUnit 规则守护)。booking 直接调 {@code DispatchService} 或访问 dispatch 的 Mapper
 * 都会形成 booking → dispatch 的反向依赖并打破模块隔离,故以本事件作为防腐层边界。
 *
 * <p><b>为什么不复用 {@link AppointmentStatusEvent}</b>:该事件的消费方就是 Booking 自己
 * ({@code BookingService.onAppointmentStatus}),用它承载"取消"会形成"自己发、自己收"的自消费环路,
 * 语义也与"体检进度回写"不符。本事件与 {@link AppointmentCreatedEvent} 同源设计:
 * 自包含快照、零跨模块回查,下游 dispatch 只依赖 {@code appointmentId} 即可定位待清理的任务。
 *
 * @param appointmentId 被取消的预约单 ID
 * @param patientId     预约所属患者 ID(便于下游按患者维度兜底清理 / 日志追踪)
 */
public record AppointmentCancelledEvent(
        Long appointmentId,
        Long patientId
) {
}
