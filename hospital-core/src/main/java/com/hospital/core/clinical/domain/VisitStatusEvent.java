package com.hospital.core.clinical.domain;

/**
 * 就诊状态变更事件，用于通知相关方。
 */
public record VisitStatusEvent(
    Long visitId,
    Long patientId,
    String patientName,
    String status,
    String message
) {}
