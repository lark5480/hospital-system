package com.hospital.core.clinical.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 医嘱(门诊就诊聚合内的实体)。
 * 一次就诊可包含多条医嘱:药品 / 检查 / 检验。与就诊同事务落库,体现强一致。
 */
@Data
@TableName("clinical.orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long visitId;

    /** MEDICATION 药品 / EXAM 检查 / LAB 检验 */
    private String type;

    private String itemName;

    private Integer quantity;

    /** 单价(元) */
    private BigDecimal unitPrice;

    /** 金额 = quantity * unitPrice,由应用层计算后落库 */
    private BigDecimal amount;

    /** 执行科室(跨科室协作,为空=开单科室执行) */
    private Long executionDeptId;

    /** CREATED / EXECUTED / CANCELLED */
    private String status;
}
