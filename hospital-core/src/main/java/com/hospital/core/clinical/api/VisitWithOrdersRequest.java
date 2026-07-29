package com.hospital.core.clinical.api;

import java.util.List;

import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 创建就诊并附带医嘱的请求载体。 */
@Data
public class VisitWithOrdersRequest {

    @NotNull(message = "就诊信息不能为空")
    @Valid
    private Visit visit;
    @NotEmpty(message = "医嘱列表不能为空")
    @Valid
    private List<Order> orders;
}
