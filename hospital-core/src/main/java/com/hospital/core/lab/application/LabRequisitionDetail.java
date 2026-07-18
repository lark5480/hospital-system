package com.hospital.core.lab.application;

import com.hospital.core.lab.domain.LabRequisition;
import com.hospital.core.lab.domain.LabResultItem;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 检验申请详情读模型:申请头 + 结果项列表。 */
@Data
@AllArgsConstructor
public class LabRequisitionDetail {
    private LabRequisition requisition;
    private List<LabResultItem> items;
}
