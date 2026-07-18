package com.hospital.core.booking.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预约单聚合根(C端患者发起)。
 * 状态机:BOOKED(已约) → CHECKED_IN(到院) → DONE(完成);CANCELLED 取消。
 * F 阶段的排队分发(Dispatch)将以本聚合为起点生成各 station 的 ExamTask。
 */
@Data
@TableName("booking.appointment")
public class Appointment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Long packageId;
    private Long slotId;

    /** BOOKED / CHECKED_IN / DONE / CANCELLED */
    private String status;

    /** UNPAID / PAID */
    private String payStatus;

    /** 实付金额(元) */
    private BigDecimal payAmount;

    private LocalDateTime createdAt;
}
