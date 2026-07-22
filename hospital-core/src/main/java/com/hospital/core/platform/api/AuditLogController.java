package com.hospital.core.platform.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.platform.domain.AuditLog;
import com.hospital.core.platform.infrastructure.AuditLogMapper;

import lombok.RequiredArgsConstructor;

/**
 * 审计日志查询接口(等保·操作留痕·医疗纠纷举证)。
 * 仅 system:admin 可见,与菜单级权限对齐。
 */
@RestController
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;

    @PreAuthorize("hasAuthority('system:admin')")
    @GetMapping("/api/core/audit-logs")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        LambdaQueryWrapper<AuditLog> q = new LambdaQueryWrapper<AuditLog>()
                .orderByDesc(AuditLog::getCreatedAt);
        if (action != null && !action.isBlank()) {
            q.eq(AuditLog::getAction, action);
        }
        if (actor != null && !actor.isBlank()) {
            q.like(AuditLog::getActor, actor);
        }
        Long total = auditLogMapper.selectCount(q);
        int offset = (pageNum - 1) * pageSize;
        q.last("LIMIT " + pageSize + " OFFSET " + offset);
        List<AuditLog> items = auditLogMapper.selectList(q);
        return ResponseEntity.ok(Map.of(
                "items", items,
                "total", total,
                "pageNum", pageNum,
                "pageSize", pageSize));
    }
}
