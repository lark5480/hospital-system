package com.hospital.core.lab.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检验申请聚合根。
 * 由医技人员从就诊的检验医嘱(Order.type=LAB)创建,与 clinical.Order 通过 orderId 追溯。
 * 状态机: PENDING → EXECUTED | CANCELLED
 */
@Data
@TableName("lab.requisition")
public class LabRequisition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long visitId;
    private Long patientId;
    private Long doctorId;

    /** 医技人员(结果录入时回填) */
    private Long technicianId;

    /** PENDING / EXECUTED / CANCELLED */
    private String status;

    /** 备注(如采样要求) */
    private String remark;

    private LocalDateTime createdAt;
    private LocalDateTime sampledAt;

    /** 报告时间(结果录入时回填) */
    private LocalDateTime reportedAt;
}
