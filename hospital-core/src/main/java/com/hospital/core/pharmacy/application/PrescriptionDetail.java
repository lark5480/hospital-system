package com.hospital.core.pharmacy.application;

import com.hospital.core.pharmacy.domain.Prescription;
import com.hospital.core.pharmacy.domain.PrescriptionItem;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 处方详情读模型:处方头 + 明细行 + 名称解析。 */
@Data
@AllArgsConstructor
public class PrescriptionDetail {
    private Prescription prescription;
    private List<PrescriptionItem> items;

    /** 患者姓名(冗余读模型) */
    private String patientName;
    /** 开方医生姓名 */
    private String doctorName;
    /** 发药药师姓名 */
    private String pharmacistName;
}
