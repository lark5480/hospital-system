package com.hospital.core.clinical.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 门诊挂号/分诊排队记录。
 * 状态:WAITING(候诊) → CALLED(已叫号,关联就诊单) / CANCELLED(取消)。
 */
@Data
@TableName("clinical.registration")
public class Registration {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Long deptId;
    /** 可选:指定医生(不指定则由科室分配)。 */
    private Long doctorId;
    /** 当日排队号(按科室自增)。 */
    private Integer queueNo;
    /** WAITING / CALLED / CANCELLED */
    private String status;
    /** 叫号后关联的就诊单 ID。 */
    private Long visitId;

    private LocalDateTime createdAt;
    private LocalDateTime calledAt;
}
