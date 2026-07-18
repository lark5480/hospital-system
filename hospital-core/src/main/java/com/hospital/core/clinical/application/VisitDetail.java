package com.hospital.core.clinical.application;

import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 就诊详情读模型:就诊 + 医嘱 + 收费 + 合计金额 + 名称解析。 */
@Data
@Builder
public class VisitDetail {

    private Visit visit;
    private List<Order> orders;
    private List<Charge> charges;
    private BigDecimal totalAmount;

    /** 患者姓名(来自 patient 模块,读模型冗余) */
    private String patientName;
    /** 医生姓名(来自 org 模块 staff) */
    private String doctorName;
    /** 科室名称(来自 org 模块 department) */
    private String deptName;

    /** 收费状态概要: ALL_PAID / HAS_UNPAID / NO_CHARGES */
    private String payStatus;
}
