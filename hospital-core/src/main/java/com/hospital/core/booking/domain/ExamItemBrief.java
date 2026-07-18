package com.hospital.core.booking.domain;

/**
 * 套餐项目简报(事件载荷用)。
 * 仅携带 Dispatch 生成任务所需的字段(station / name / orderNo),
 * 避免把 booking 的领域实体 ExamItem 泄漏到其他模块边界之外。
 */
public record ExamItemBrief(String station, String name, int orderNo) {
}
