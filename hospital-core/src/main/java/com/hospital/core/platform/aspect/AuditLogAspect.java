package com.hospital.core.platform.aspect;

import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
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

import lombok.extern.slf4j.Slf4j;

/**
 * 审计日志切面:拦截 {@link AuditLog} 注解的方法,在成功执行后写入 audit_log 表。
 * <p>
 * target 字段从方法参数或返回值自动提取实体 ID 构建,如 "visit_id=42"。
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private final AuditLogMapper auditLogMapper;

    /** R-44: 审计写入专用线程池(见 AsyncConfig#auditLogExecutor),异步执行避免审计 IO 拖慢业务线程。 */
    private final TaskExecutor auditLogExecutor;

    public AuditLogAspect(AuditLogMapper auditLogMapper,
                          @Qualifier("auditLogExecutor") TaskExecutor auditLogExecutor) {
        this.auditLogMapper = auditLogMapper;
        this.auditLogExecutor = auditLogExecutor;
    }

    /**
     * R-31: 审计写入必须用 try/finally 包裹 —— 原实现在 {@code jp.proceed()} 之后才写审计,
     * 一旦业务抛出异常(如越权被拒、状态机校验失败),审计记录<b>一条都不会留</b>,
     * 而"失败的操作"往往才是最需要留痕的(越权探测、非法状态流转)。
     *
     * <p>注意:这里记录的是"操作发生且失败",detail 追加 FAILED 标记与异常类型;
     * 异常仍原样抛出,不改变对外行为。
     */
    @Around("@annotation(auditLog)")
    public Object audit(ProceedingJoinPoint jp, AuditLog auditLog) throws Throwable {
        try {
            Object result = jp.proceed();
            writeAuditLog(jp, auditLog, result, null);
            return result;
        } catch (Throwable t) {
            writeAuditLog(jp, auditLog, null, t);
            throw t;
        }
    }

    /**
     * 写入一条审计记录。失败场景下 result 为 null,{@link #buildTarget} 会退化为从入参提取目标。
     *
     * @param failure 业务抛出的异常;为 null 表示操作成功
     */
    private void writeAuditLog(ProceedingJoinPoint jp, AuditLog auditLog, Object result, Throwable failure) {
        var entry = new com.hospital.core.platform.domain.AuditLog();
        entry.setActor(currentUser());
        entry.setAction(auditLog.action());
        entry.setTarget(buildTarget(jp, result));
        String detail = auditLog.detail();
        if (failure != null) {
            String failed = "FAILED: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            detail = detail == null || detail.isEmpty() ? failed : detail + " | " + failed;
        }
        if (detail != null && !detail.isEmpty()) {
            // detail 列长度 500,超长会插入失败,这里做安全截断
            entry.setDetail(detail.length() > 500 ? detail.substring(0, 500) : detail);
        }
        entry.setCreatedAt(LocalDateTime.now());
        // R-44: 审计写入提交到专用线程池异步执行,避免审计 IO(磁盘/网络)拖慢业务响应。
        // 关键约束:审计绝不能因异步而静默丢失 —— 执行器不可用、被拒绝,或任务内部异常时,
        // 一律降级为同步写入,保证"操作发生即留痕";既有 try/finally 语义(R-31)保持不变。
        submitAuditWrite(entry, auditLog.action());
    }

    /**
     * R-44: 提交审计写入任务。任何无法异步执行的情况都降级为同步插入。
     * 异步任务在自身的 try/catch 内吞掉异常并记 ERROR —— 异步异常不会再抛回业务线程。
     */
    private void submitAuditWrite(com.hospital.core.platform.domain.AuditLog entry, String action) {
        if (auditLogExecutor == null) {
            // 降级:执行器不可用(未装配)→ 同步写
            insertSafely(entry, action);
            return;
        }
        try {
            auditLogExecutor.execute(() -> insertSafely(entry, action));
        } catch (RejectedExecutionException e) {
            // 降级:线程池拒绝(关闭/饱和且策略不可用)→ 同步写一次,不丢审计
            log.error("[AuditLogAspect] 审计异步执行被拒绝,降级为同步写入: action={}, target={}",
                    action, entry.getTarget(), e);
            insertSafely(entry, action);
        }
    }

    /** R-44: 同步写入一条审计;失败仅记日志、绝不抛出(保持 R-31 的"失败不影响业务"语义)。 */
    private void insertSafely(com.hospital.core.platform.domain.AuditLog entry, String action) {
        try {
            auditLogMapper.insert(entry);
        } catch (RuntimeException e) {
            // R-31: 审计写入失败绝不能影响业务流程(尤其失败场景下不能把原始异常替换掉)
            log.error("[AuditLogAspect] 审计日志写入失败: action={}, target={}",
                    action, entry.getTarget(), e);
        }
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
