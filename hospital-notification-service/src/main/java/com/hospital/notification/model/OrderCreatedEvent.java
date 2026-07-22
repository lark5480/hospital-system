package com.hospital.notification.model;

/**
 * 医嘱创建事件（防腐层：core 事件副本）。
 */
public record OrderCreatedEvent(
    Long visitId,
    Long patientId,
    String patientName,
    String orderType,
    String itemName,
    String targetRole,
    Long executionDeptId
) {}
