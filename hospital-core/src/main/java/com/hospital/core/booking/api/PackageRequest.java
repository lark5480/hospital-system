package com.hospital.core.booking.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 创建体检套餐请求载体。 */
@Data
public class PackageRequest {

    @NotBlank(message = "套餐名称不能为空")
    private String name;
    private BigDecimal price;
    private String description;
}
