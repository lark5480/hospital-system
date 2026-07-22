package com.hospital.notification.model;

import java.time.LocalDateTime;

/**
 * 通知服务收到领域事件后留痕的内存记录(仅用于演示事件驱动链路与前端展示,
 * 真实系统应落库并对接短信/邮件/站内信渠道)。
 */
public record NotificationRecord(
        Long id,
        String type,
        Long visitId,
        Long patientId,
        Long doctorId,
        LocalDateTime eventTime,
        LocalDateTime receivedAt,
        String channel,
        String content,
        String targetRole,
        Long targetDeptId
) {
}
