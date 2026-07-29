package com.hospital.core.report.api;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 创建报告请求。 */
@Data
public class CreateReportRequest {
    @NotNull(message = "就诊ID不能为空")
    private Long visitId;
    private String type;
    private String title;
    private String content;
    private Long doctorId;
}
