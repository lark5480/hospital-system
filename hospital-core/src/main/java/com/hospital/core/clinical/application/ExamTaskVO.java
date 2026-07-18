package com.hospital.core.clinical.application;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 待执行检查 VO。 */
@Data
@AllArgsConstructor
public class ExamTaskVO {
    private Long orderId;
    private Long visitId;
    private String patientName;
    private String doctorName;
    private String itemName;
}
