package com.hospital.core.platform.aspect;

import com.hospital.core.booking.api.AppointmentDetail;
import com.hospital.core.clinical.application.VisitDetail;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.infrastructure.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

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
        // addOrder(id, …), pay(id): 第一个 Long 参数即 visitId
        for (Object arg : jp.getArgs()) {
            if (arg instanceof Long id) return "visit_id=" + id;
        }
        // create(visit), createWithOrders(req), book(req): 从 ResponseEntity body 提取
        if (result instanceof ResponseEntity<?> re && re.getBody() != null) {
            Object body = re.getBody();
            if (body instanceof Visit v)               return "visit_id=" + v.getId();
            if (body instanceof VisitDetail d)          return "visit_id=" + d.getVisit().getId();
            if (body instanceof AppointmentDetail d)    return "appointment_id=" + d.getId();
        }
        return jp.getSignature().getName();
    }
}
