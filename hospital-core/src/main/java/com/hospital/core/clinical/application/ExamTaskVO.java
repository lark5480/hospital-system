package com.hospital.core.clinical.application;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 检查任务 VO(待执行/已完成)。 */
@Data
@AllArgsConstructor
public class ExamTaskVO {
    private Long orderId;
    private Long visitId;
    private String patientName;
    private String doctorName;
    private String itemName;
    private String status;
    private String finding;
}
