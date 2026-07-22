package com.hospital.notification.model;

/**
 * 就诊状态变更事件（防腐层：core 事件副本）。
 */
public record VisitStatusEvent(
    Long visitId,
    Long patientId,
    String patientName,
    String status,
    String message
) {}
