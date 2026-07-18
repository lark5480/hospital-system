package com.hospital.core.clinical.api;

import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import lombok.Data;

import java.util.List;

/** 创建就诊并附带医嘱的请求载体。 */
@Data
public class VisitWithOrdersRequest {

    private Visit visit;
    private List<Order> orders;
}
