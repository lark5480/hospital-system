package com.hospital.core.clinical.domain;

/**
 * 医嘱创建事件，用于通知执行科室/人员。
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
