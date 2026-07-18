package com.hospital.core.platform.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户×角色(多对多):一个账号可同时是医生+患者+管理员。
 */
@Data
@TableName("platform.sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String roleCode;

    private LocalDateTime createdAt;
}
