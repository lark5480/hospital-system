package com.hospital.core.platform.aspect;

import java.time.LocalDateTime;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.hospital.core.booking.api.AppointmentDetail;
import com.hospital.core.clinical.application.VisitDetail;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.iam.domain.Menu;
import com.hospital.core.org.domain.Department;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.domain.Role;
import com.hospital.core.platform.infrastructure.AuditLogMapper;

import lombok.RequiredArgsConstructor;

/**
 * 审计日志切面:拦截 {@link AuditLog} 注解的方法,在成功执行后写入 audit_log 表。
 * <p>
 * target 字段从方法参数或返回值自动提取实体 ID 构建,如 "visit_id=42"。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogMapper auditLogMapper;

    @Around("@annotation(auditLog)")
    public Object audit(ProceedingJoinPoint jp, AuditLog auditLog) throws Throwable {
        Object result = jp.proceed();

        var log = new com.hospital.core.platform.domain.AuditLog();
        log.setActor(currentUser());
        log.setAction(auditLog.action());
        log.setTarget(buildTarget(jp, result));
        if (!auditLog.detail().isEmpty()) {
            log.setDetail(auditLog.detail());
        }
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);

        return result;
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "system";
    }

    /** 从方法参数或返回值中提取目标实体 ID。 */
    private static String buildTarget(ProceedingJoinPoint jp, Object result) {
        // 1. 优先从 ResponseEntity 返回值提取实体 ID（create / update 场景）
        if (result instanceof ResponseEntity<?> re && re.getBody() != null) {
            Object body = re.getBody();
            if (body instanceof Visit v)               return "visit_id=" + v.getId();
            if (body instanceof VisitDetail d)          return "visit_id=" + d.getVisit().getId();
            if (body instanceof AppointmentDetail d)    return "appointment_id=" + d.getId();
            if (body instanceof Department d)           return "dept_id=" + d.getId();
            if (body instanceof Staff s)                return "staff_id=" + s.getId();
            if (body instanceof Menu m)                 return "menu_id=" + m.getId();
            if (body instanceof Role r)                 return "role_code=" + r.getCode();
        }
        // 1b. 从 ResponseEntity<Map> 提取 role code（saveAuthorities 场景）
        if (result instanceof ResponseEntity<?> re && re.getBody() instanceof java.util.Map<?, ?> map) {
            Object updated = map.get("updated");
            if (updated instanceof String roleCode) return "role_code=" + roleCode;
        }
        // 2. 从方法参数提取: 第一个 Long 参数作为 ID（delete / 单参数操作场景）
        for (Object arg : jp.getArgs()) {
            if (arg instanceof Long id) {
                String name = jp.getSignature().getName();
                // 按 action 名推断实体类型
                String action = "";
                for (var ann : ((org.aspectj.lang.reflect.MethodSignature) jp.getSignature()).getMethod().getAnnotations()) {
                    if (ann instanceof AuditLog al) { action = al.action(); break; }
                }
                if (action.contains("DEPT"))     return "dept_id=" + id;
                if (action.contains("STAFF"))    return "staff_id=" + id;
                if (action.contains("MENU"))     return "menu_id=" + id;
                if (action.contains("ROLE"))     return "role_id=" + id;
                return "visit_id=" + id;
            }
        }
        return jp.getSignature().getName();
    }
}
