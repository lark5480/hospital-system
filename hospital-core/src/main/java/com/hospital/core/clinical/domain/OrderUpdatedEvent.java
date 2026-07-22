package com.hospital.core.clinical.domain;

import java.math.BigDecimal;

/**
 * 医嘱修改事件:医嘱的项目名/数量/单价被修改后发布。
 * 下游模块(pharmacy 处方、lab 检验申请)据此同步各自 PENDING 明细的快照字段,
 * 避免医生二次修改医嘱后,处方/申请仍显示旧名称。
 */
public record OrderUpdatedEvent(
    Long orderId,
    String itemName,
    Integer quantity,
    BigDecimal unitPrice
) {}
