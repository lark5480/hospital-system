package com.hospital.core.clinical.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 就诊读模型（CQRS-lite）。
 * 写操作同一事务内同步更新，读查询直接走此表，避免 N+1 和内存分页。
 */
@Data
@TableName("clinical.visit_read_model")
public class VisitReadModel {
    private Long id;
    private Long visitId;
    private Long patientId;
    private Long doctorId;
    private Long deptId;
    private String chiefComplaint;
    private String status;
    private LocalDateTime visitTime;
    private LocalDateTime createdAt;
    
    // 物化字段（写时计算）
    private String patientName;
    private String doctorName;
    private String deptName;
    private Integer orderCount;
    private BigDecimal totalAmount;
    private String payStatus;      // NO_CHARGES / HAS_UNPAID / ALL_PAID
    private Integer unpaidCount;
}
