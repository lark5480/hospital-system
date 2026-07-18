package com.hospital.core.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工聚合根。
 * username 关联登录账号,通过 position 区分岗位。
 * password 为 BCrypt 哈希,不序列化给前端(JsonIgnore)。
 */
@Data
@TableName("org.staff")
public class Staff {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String gender;
    private String phone;
    private Long deptId;
    private String position;
    private String username;

    @JsonIgnore
    private String password;

    private Long userId;  // 关联统一账号(platform.sys_user)

    private String status;
    private LocalDateTime createdAt;
}
