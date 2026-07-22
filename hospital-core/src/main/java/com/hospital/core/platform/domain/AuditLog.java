package com.hospital.core.platform.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 共享内核:审计日志(最小化示例)。
 * 平台基础是各模块唯一允许直接依赖的共享内核。
 */
@Data
@TableName("platform.audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String actor;
    private String action;
    private String target;
    private String detail;
    private LocalDateTime createdAt;
}
