package com.hospital.core.lab.api;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 创建检验申请请求。orderIds 为空时自动拾取该就诊下全部 LAB+CREATED 医嘱。 */
@Data
public class CreateRequisitionRequest {
    @NotNull(message = "就诊ID不能为空")
    private Long visitId;
    @NotNull(message = "医生ID不能为空")
    private Long doctorId;
    @NotEmpty(message = "医嘱ID不能为空")
    private List<Long> orderIds;
}
