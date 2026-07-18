package com.hospital.core.pharmacy.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 处方明细(单个药品项)。
 * 从 clinical.Order(type=MEDICATION) 同步创建,通过 orderId 保持追溯。
 */
@Data
@TableName("pharmacy.prescription_item")
public class PrescriptionItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属处方 */
    private Long prescriptionId;

    /** 关联临床医嘱 ID(clinical.orders.id) */
    private Long orderId;

    /** 药品名称 */
    private String itemName;

    /** 数量 */
    private Integer quantity;

    /** 单价 */
    private BigDecimal unitPrice;

    /** PENDING / DISPENSED / CANCELLED */
    private String status;
}
