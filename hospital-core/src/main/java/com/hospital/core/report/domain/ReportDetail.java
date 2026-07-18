package com.hospital.core.report.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 报告详情读模型。
 * 继承 Report 实体全部字段,并解析关联名称:患者(姓名/性别/电话) + 就诊单(医生/科室/主诉),
 * 供前端直接展示,避免前端再拼多次请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReportDetail extends Report {

    /** 患者信息(按 patientId 解析) */
    private String patientName;
    private String patientGender;
    private String patientPhone;

    /** 就诊单信息(按 visitId 解析) */
    private String doctorName;
    private String deptName;
    private String visitChiefComplaint;
}
