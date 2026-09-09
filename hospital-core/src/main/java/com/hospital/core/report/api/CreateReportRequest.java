package com.hospital.core.report.api;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建报告请求。
 *
 * <p>R-09: 已移除 doctorId —— 报告医生必须由服务端按当前登录用户解析,
 * 前端传入会导致冒名医生发布报告。
 */
@Data
public class CreateReportRequest {
    @NotNull(message = "就诊ID不能为空")
    private Long visitId;
    private String type;
    private String title;
    private String content;
}
