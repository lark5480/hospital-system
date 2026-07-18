package com.hospital.core.patient.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 患者聚合根(C端身份)。
 * 一个人可对应一个主账户;真实系统里还会有一人多"就诊人"的从表,这里先用最小骨架演示 C 端身份与预约关联。
 */
@Data
@TableName("patient.patient")
public class Patient {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String gender;
    private LocalDate birthday;
    private String phone;
    private String idCard;
    private String username;
    private Long userId;  // 关联统一账号(platform.sys_user)
    private LocalDateTime createdAt;
}
