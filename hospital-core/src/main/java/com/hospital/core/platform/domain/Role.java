package com.hospital.core.platform.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RBAC 角色(对应医护岗位)。
 * 角色本身不绑定菜单,而是通过 {@link RoleAuthority} 持有 authority 串列表;
 * authority 同时用于侧边栏菜单可见性过滤 + 后端 @PreAuthorize 接口鉴权。
 */
@Data
@TableName("platform.role")
public class Role {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码:DOCTOR / NURSE / PHARMACIST / CASHIER / ADMIN */
    private String code;

    /** 角色名称:医师 / 护士 / 药师 / 收费员 / 管理员 */
    private String name;

    private String description;

    private LocalDateTime createdAt;
}
