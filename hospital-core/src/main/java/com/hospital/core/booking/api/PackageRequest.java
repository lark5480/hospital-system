package com.hospital.core.booking.api;

import lombok.Data;

import java.math.BigDecimal;

/** 创建体检套餐请求载体。 */
@Data
public class PackageRequest {

    private String name;
    private BigDecimal price;
    private String description;
}
