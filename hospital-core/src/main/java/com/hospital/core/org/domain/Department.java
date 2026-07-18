package com.hospital.core.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 科室聚合根。
 */
@Data
@TableName("org.department")
public class Department {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String code;
    private String description;

    private LocalDateTime createdAt;
}
