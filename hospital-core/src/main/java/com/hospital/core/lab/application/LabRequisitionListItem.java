package com.hospital.core.lab.application;

import com.hospital.core.lab.domain.LabRequisition;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 检验申请列表读模型:包含患者/医生名称,用于列表展示。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LabRequisitionListItem extends LabRequisition {
    private String patientName;
    private String doctorName;
}
