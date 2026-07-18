package com.hospital.core.pharmacy.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 处方聚合根。
 * 由药师从就诊的药品医嘱(Order.type=MEDICATION)创建,与 clinical.Order 通过 orderId 追溯。
 * 状态机: PENDING → DISPENSING → DISPENSED | CANCELLED
 */
@Data
@TableName("pharmacy.prescription")
public class Prescription {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联就诊 */
    private Long visitId;

    /** 患者 */
    private Long patientId;

    /** 开方医生 */
    private Long doctorId;

    /** 发药药师(dispense 时回填) */
    private Long pharmacistId;

    /** PENDING / DISPENSING / DISPENSED / CANCELLED */
    private String status;

    /** 备注(如用药指导) */
    private String remark;

    private LocalDateTime createdAt;

    /** 发药时间(dispense 时回填) */
    private LocalDateTime dispensedAt;
}
