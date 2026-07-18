package com.hospital.core.clinical.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hospital.core.clinical.infrastructure.JsonbTypeHandler;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 结构化病历（门诊病历）。
 * 与 Visit 一对一关联，使用 JSONB 存储半结构化数据。
 */
@Data
@TableName(value = "clinical.medical_record", autoResultMap = true)
public class MedicalRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long visitId;
    private Long patientId;
    private Long doctorId;
    private Long deptId;

    // 结构化字段
    private String chiefComplaint;      // 主诉
    private String presentIllness;      // 现病史
    private String pastHistory;         // 既往史
    private String familyHistory;       // 家族史
    private String allergyHistory;      // 过敏史

    // JSONB 字段
    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> physicalExam;     // 体格检查

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> auxiliaryExam;  // 辅助检查

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> diagnosis;      // 诊断

    private String treatmentPlan;        // 治疗计划

    // 元数据
    private String status;               // DRAFT / FINAL
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finalizedAt;   // 终诊时间
}
