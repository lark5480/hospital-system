package com.hospital.core.report.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 报告聚合根。
 * 作为就诊/检验/检查结果的统一报告载体,按 type 区分来源域。
 */
@Data
@TableName("report.record")
public class Report {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联就诊 */
    private Long visitId;

    /** 患者ID(供C端按患者查报告,不与visit绑定) */
    private Long patientId;

    /** 关联体检预约ID(EXAM 报告溯源,防止同一预约重复出报告) */
    private Long appointmentId;

    /** 类型:LAB(检验报告) / EXAM(检查报告) / CLINICAL(门诊病历) */
    private String type;

    /** 标题 */
    private String title;

    /** 报告内容(Markdown / HTML) */
    private String content;

    /** 报告医生 */
    private Long doctorId;

    /** DRAFT / PUBLISHED */
    private String status;

    private LocalDateTime createdAt;

    /** 报告发布时间 */
    private LocalDateTime publishedAt;

    /** PDF 文件在 MinIO 的对象名(reports/{reportId}.pdf);为空表示尚未生成 PDF。 */
    private String fileId;

    /** PDF 生成状态:PENDING(排队/生成中) / READY(已生成) / FAILED(生成失败)。 */
    private String pdfStatus;
}
