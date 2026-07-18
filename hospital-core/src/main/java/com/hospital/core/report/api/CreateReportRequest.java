package com.hospital.core.report.api;

import lombok.Data;

/** 创建报告请求。 */
@Data
public class CreateReportRequest {
    private Long visitId;
    private String type;
    private String title;
    private String content;
    private Long doctorId;
}
