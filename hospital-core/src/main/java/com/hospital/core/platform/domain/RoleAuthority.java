package com.hospital.core.platform.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色↔权限映射。一个角色可拥有多个 authority,决定:
 * - 侧边栏菜单可见性(MenuService 过滤)
 * - 后端接口 @PreAuthorize 鉴权
 */
@Data
@TableName("platform.role_authority")
public class RoleAuthority {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码,关联 platform.role.code */
    private String roleCode;

    /** 权限串,如 visit:entry / order:execute / pharmacy:dispense */
    private String authority;

    private LocalDateTime createdAt;
}
