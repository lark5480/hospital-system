package com.hospital.core.pharmacy.api;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 创建处方请求:指定就诊与开方医生。 */
@Data
public class CreatePrescriptionRequest {
    @NotNull(message = "就诊ID不能为空")
    private Long visitId;
    private Long doctorId;
}
