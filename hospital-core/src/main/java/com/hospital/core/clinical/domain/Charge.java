package com.hospital.core.clinical.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收费记录。每笔医嘱在执行时生成一条收费,与就诊同事务落库(强一致,无需 Saga)。
 */
@Data
@TableName("clinical.charge")
public class Charge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long visitId;

    /** 关联医嘱(汇总收费时可为空) */
    private Long orderId;

    private String itemName;

    private BigDecimal amount;

    /** UNPAID 未付 / PAID 已付 */
    private String payStatus;

    private LocalDateTime payTime;
}
