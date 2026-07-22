package com.hospital.core.lab.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 检验结果项(单个检验结果)。
 * 从 clinical.Order(type=LAB) 同步创建,通过 orderId 保持追溯。
 */
@Data
@TableName("lab.result_item")
public class LabResultItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属检验申请 */
    private Long requisitionId;

    /** 关联临床医嘱 ID(clinical.orders.id) */
    private Long orderId;

    /** 检验项目名称 */
    private String itemName;

    /** 检验结果值 */
    private String resultValue;

    /** 单位(如 mmol/L) */
    private String unit;

    /** 参考范围 */
    private String refRange;

    /** 异常标记:NORMAL / HIGH / LOW */
    private String abnormalFlag;

    /** PENDING / COMPLETED */
    private String status;
}
