package com.hospital.core.booking.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 体检套餐聚合根。一个套餐包含多个体检项目(ExamItem)。 */
@Data
@TableName("booking.exam_package")
public class ExamPackage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 套餐总价(元) */
    private BigDecimal price;

    private String description;

    private LocalDateTime createdAt;
}
