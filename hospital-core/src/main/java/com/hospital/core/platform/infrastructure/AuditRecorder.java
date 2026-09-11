package com.hospital.core.platform.infrastructure;

import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.hospital.core.platform.domain.AuditLog;

import lombok.extern.slf4j.Slf4j;

/**
 * 审计写入器 —— "操作发生即留痕"的统一出口。
 *
 * <p><b>为什么要把这段逻辑抽出来</b>:它原先只活在 {@code AuditLogAspect} 里,而切面只能拦截
 * <b>Controller</b> 方法。R-64 把"确单 → 生成检验申请/处方"从浏览器收回服务端之后,生成由事件监听器
 * 与对账任务执行,<b>根本不再经过 Controller</b> —— 切面自然一条都记不到,
 * {@code CREATE_REQUISITION} / {@code CREATE_PRESCRIPTION} 会从审计轨迹里彻底消失。
 * 所以把"写审计"抽成公共组件,让切面与事件监听器/定时任务共用同一套语义,
 * 而不是在监听器里另写一套(那会出现两种异步策略、两种失败降级行为)。
 *
 * <p><b>为什么放在 infrastructure</b>:它要直接写 {@code platform.domain.AuditLog},
 * 而 ArchUnit 的分层规则只允许 api / application / infrastructure 访问 domain 层
 * (放 {@code platform.support} 会直接构建失败)。与 {@link AuditLogMapper} 同层也符合语义:
 * 这里是"审计落库"的入口。
 *
 * <p>语义完全沿用 {@code AuditLogAspect} 既有的两条约束:
 * <ul>
 *   <li><b>R-31</b>:审计写入失败<b>绝不能</b>影响业务流程 —— 一律吞掉并记 ERROR;</li>
 *   <li><b>R-44</b>:默认异步写(专用线程池),但绝不因异步而静默丢失 ——
 *       执行器为 null、被拒绝,或任务内部异常时,一律降级为同步写入。</li>
 * </ul>
 */
@Slf4j
@Component
public class AuditRecorder {

    private final AuditLogMapper auditLogMapper;

    /** R-44: 审计写入专用线程池(见 AsyncConfig#auditLogExecutor)。 */
    private final TaskExecutor auditLogExecutor;

    public AuditRecorder(AuditLogMapper auditLogMapper,
                         @Qualifier("auditLogExecutor") TaskExecutor auditLogExecutor) {
        this.auditLogMapper = auditLogMapper;
        this.auditLogExecutor = auditLogExecutor;
    }

    /**
     * 以当前安全上下文的用户(取不到则 {@code system})记录一条审计。
     *
     * <p>注意 actor 的取值时机:事件监听器是 {@code AFTER_COMMIT} 且非 {@code @Async},
     * 仍在处理该请求的线程上执行,故能取到真实操作者;而定时对账任务没有安全上下文,
     * 自然落到 {@code system} —— 这正好把"人工触发"与"系统补建"区分开。
     *
     * @param action 动作名(与 Controller 路径保持一致,便于沿用既有审计查询)
     * @param target 目标描述,如 {@code visit_id=323}
     * @param detail 明细;超过 500 字会被截断(detail 列长度限制)
     */
    public void record(String action, String target, String detail) {
        AuditLog entry = new AuditLog();
        entry.setActor(currentActor());
        entry.setAction(action);
        entry.setTarget(target);
        if (detail != null && !detail.isEmpty()) {
            entry.setDetail(detail.length() > 500 ? detail.substring(0, 500) : detail);
        }
        entry.setCreatedAt(LocalDateTime.now());
        record(entry);
    }

    /** 写入一条已构建好的审计记录(异步 + 不可用时降级同步)。 */
    public void record(AuditLog entry) {
        if (auditLogExecutor == null) {
            // 降级:执行器不可用(未装配)→ 同步写
            insertSafely(entry);
            return;
        }
        try {
            auditLogExecutor.execute(() -> insertSafely(entry));
        } catch (RejectedExecutionException e) {
            // 降级:线程池拒绝(关闭/饱和且策略不可用)→ 同步写一次,不丢审计
            log.error("[Audit] 审计异步执行被拒绝,降级为同步写入: action={}, target={}",
                    entry.getAction(), entry.getTarget(), e);
            insertSafely(entry);
        }
    }

    /** 同步写入一条审计;失败仅记日志、绝不抛出(保持 R-31 的"失败不影响业务"语义)。 */
    private void insertSafely(AuditLog entry) {
        try {
            auditLogMapper.insert(entry);
        } catch (RuntimeException e) {
            log.error("[Audit] 审计日志写入失败: action={}, target={}",
                    entry.getAction(), entry.getTarget(), e);
        }
    }

    /** 当前操作者;无认证上下文(定时任务/匿名)时返回 {@code system}。 */
    public static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "system";
    }
}
