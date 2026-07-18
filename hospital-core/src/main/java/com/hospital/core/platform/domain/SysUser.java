package com.hospital.core.platform.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一账号:手机号 + 密码登录,一个账号可绑定多角色。
 */
@Data
@TableName("platform.sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;

    @JsonIgnore
    private String password;

    private String name;

    private String status;

    private LocalDateTime createdAt;
}
