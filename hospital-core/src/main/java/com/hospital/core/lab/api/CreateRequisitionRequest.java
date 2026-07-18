package com.hospital.core.lab.api;

import lombok.Data;
import java.util.List;

/** 创建检验申请请求。orderIds 为空时自动拾取该就诊下全部 LAB+CREATED 医嘱。 */
@Data
public class CreateRequisitionRequest {
    private Long visitId;
    private Long doctorId;
    private List<Long> orderIds;
}
