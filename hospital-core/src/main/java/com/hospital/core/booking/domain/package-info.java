/**
 * Booking 模块的领域层——对外暴露供其他模块订阅的领域事件。
 * <p>
 * 包含 {@code AppointmentCreatedEvent}、{@code ExamItemBrief} 等自包含事件快照，
 * dispatch 模块通过 {@code @TransactionalEventListener} 消费。
 */
@org.springframework.modulith.NamedInterface("domain")
package com.hospital.core.booking.domain;
