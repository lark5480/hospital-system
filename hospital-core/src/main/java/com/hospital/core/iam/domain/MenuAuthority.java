package com.hospital.core.iam.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("platform.menu_authority")
public class MenuAuthority {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long menuId;
    private String authority;
}
