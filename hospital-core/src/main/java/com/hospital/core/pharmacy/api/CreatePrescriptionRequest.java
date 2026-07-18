package com.hospital.core.pharmacy.api;

import lombok.Data;

/** 创建处方请求:指定就诊与开方医生。 */
@Data
public class CreatePrescriptionRequest {
    private Long visitId;
    private Long doctorId;
}
