package com.hospital.core.iam.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("platform.menu")
public class Menu {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;
    private String key;
    private String title;
    private String path;
    private String icon;
    private Integer sortOrder;
    private Boolean visible;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private List<Menu> children;

    @TableField(exist = false)
    private List<String> authorities;
}
