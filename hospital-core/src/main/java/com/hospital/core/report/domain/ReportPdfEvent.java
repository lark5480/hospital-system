package com.hospital.core.report.domain;

/**
 * 报告 PDF 生成请求事件。体检全部完成后由 dispatch 模块发布,
 * report 模块的异步监听器消费并生成 PDF 上传至 MinIO。
 * 事件为自包含快照(携带生成所需的最小字段),避免监听器回查 dispatch。
 */
public record ReportPdfEvent(
        Long reportId,
        Long patientId,
        String title,
        String content
) {
}
