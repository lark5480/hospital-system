package com.hospital.core.clinical.domain;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

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
    @NotBlank(message = "医嘱类型不能为空")
    private String type;

    @NotBlank(message = "医嘱项目名称不能为空")
    private String itemName;

    @Min(value = 1, message = "数量必须大于0")
    private Integer quantity;

    /** 单价(元) */
    @DecimalMin(value = "0.01", message = "单价必须大于0")
    private BigDecimal unitPrice;

    /** 金额 = quantity * unitPrice,由应用层计算后落库 */
    private BigDecimal amount;

    /** 执行科室(跨科室协作,为空=开单科室执行) */
    private Long executionDeptId;

    /** CREATED / EXECUTED / CANCELLED */
    private String status;

    /** 检查所见/结果(仅 EXAM 类医嘱) */
    private String finding;
}
