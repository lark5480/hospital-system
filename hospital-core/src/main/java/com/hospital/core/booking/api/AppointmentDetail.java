package com.hospital.core.booking.api;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 预约详情读模型:聚合预约单 + 套餐名 + 号源日期/时段 + 付费信息。 */
@Data
public class AppointmentDetail {

    private Long id;
    private Long patientId;
    private Long packageId;
    private String packageName;
    private Long slotId;
    private String status;
    private String payStatus;
    private BigDecimal payAmount;
    private LocalDateTime examDate;
    private String period;
    private LocalDateTime createdAt;
}
