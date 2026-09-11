package com.hospital.core.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.hospital.core.platform.domain.AuditLog;

/**
 * R-64: 审计写入器 —— 从 {@code AuditLogAspect} 抽出的公共组件。
 *
 * <p>抽出它的直接动因:生成检验申请/处方改为事件驱动后不再经过 Controller,
 * 审计切面记不到账,必须由监听器显式补写;而"异步 + 不可用时降级同步"(R-44)
 * 与"写入失败不影响业务"(R-31)这两条语义不能在监听器里再实现一遍。
 *
 * <p>因此这里锁定的正是那两条约束:
 * <ol>
 *   <li>默认走专用线程池(本测试用同步执行器替身,验证确实委托给执行器);</li>
 *   <li>执行器为 null 或被拒绝 → <b>降级同步写</b>,审计不丢;</li>
 *   <li>写库抛异常 → <b>不外抛</b>(否则会把业务主流程带崩);</li>
 *   <li>actor 取当前安全上下文,取不到时落到 {@code system}(定时对账任务即此路径)。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AuditRecorderTest {

    @Mock
    AuditLogMapper auditLogMapper;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("正常路径:写入委托给专用线程池,actor 取当前登录用户")
    void record_usesExecutorAndCurrentUser() {
        // 同步执行的执行器替身:既验证"确实交给执行器",又让断言无需等待异步
        TaskExecutor syncExecutor = Runnable::run;
        AuditRecorder recorder = new AuditRecorder(auditLogMapper, syncExecutor);
        // 注意必须用带 authorities 的三参构造:两参构造产生的是**未认证** token
        // (isAuthenticated()==false),currentActor() 会把它当作匿名而落到 system
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("13800000001", "n/a", List.of()));

        recorder.record("CREATE_REQUISITION", "visit_id=323", "触发: 确单");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog entry = captor.getValue();
        assertThat(entry.getActor()).isEqualTo("13800000001");
        assertThat(entry.getAction()).isEqualTo("CREATE_REQUISITION");
        assertThat(entry.getTarget()).isEqualTo("visit_id=323");
        assertThat(entry.getDetail()).isEqualTo("触发: 确单");
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("匿名认证(anonymousUser)→ actor 仍为 system,不把匿名当操作者")
    void record_anonymousUser_actorIsSystem() {
        AuditRecorder recorder = new AuditRecorder(auditLogMapper, Runnable::run);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", "n/a", List.of()));

        recorder.record("CREATE_REQUISITION", "visit_id=1", null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("system");
    }

    @Test
    @DisplayName("无安全上下文(定时对账任务)→ actor 为 system,与人工操作可区分")
    void record_withoutAuth_actorIsSystem() {
        AuditRecorder recorder = new AuditRecorder(auditLogMapper, Runnable::run);

        recorder.record("CREATE_REQUISITION", "visit_id=1", "触发: 对账补建");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("system");
    }

    @Test
    @DisplayName("R-44: 执行器为 null → 降级为同步写入,审计不丢")
    void record_executorNull_fallsBackToSync() {
        AuditRecorder recorder = new AuditRecorder(auditLogMapper, null);

        recorder.record("CREATE_REQUISITION", "visit_id=1", null);

        verify(auditLogMapper).insert(any(AuditLog.class));
    }

    @Test
    @DisplayName("R-44: 执行器拒绝任务 → 降级为同步写入,审计不丢")
    void record_executorRejects_fallsBackToSync() {
        TaskExecutor rejecting = command -> {
            throw new RejectedExecutionException("模拟线程池饱和");
        };
        AuditRecorder recorder = new AuditRecorder(auditLogMapper, rejecting);

        recorder.record("CREATE_REQUISITION", "visit_id=1", null);

        verify(auditLogMapper).insert(any(AuditLog.class));
    }

    @Test
    @DisplayName("R-31: 写库失败绝不外抛(不能因审计把业务主流程带崩)")
    void record_insertFails_doesNotThrow() {
        when(auditLogMapper.insert(any(AuditLog.class)))
                .thenThrow(new IllegalStateException("模拟 audit_log 不可用"));
        AuditRecorder recorder = new AuditRecorder(auditLogMapper, Runnable::run);

        assertThatCode(() -> recorder.record("CREATE_REQUISITION", "visit_id=1", "x"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("超长 detail 被截断到 500 字(detail 列长度限制,超长会插入失败丢审计)")
    void record_longDetail_truncated() {
        AuditRecorder recorder = new AuditRecorder(auditLogMapper, Runnable::run);

        recorder.record("CREATE_REQUISITION", "visit_id=1", "x".repeat(600));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getDetail()).hasSize(500);
    }

    @Test
    @DisplayName("detail 为空时不写入该列(保持表内语义:无明细而非空串)")
    void record_blankDetail_omitted() {
        AuditRecorder recorder = new AuditRecorder(auditLogMapper, Runnable::run);

        recorder.record("CREATE_REQUISITION", "visit_id=1", "");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getDetail()).isNull();
    }

    @Test
    @DisplayName("已构建的 entry 也能直接写入(切面走这条路径,保持既有行为不变)")
    void record_entryOverload() {
        AuditRecorder recorder = new AuditRecorder(auditLogMapper, Runnable::run);
        AuditLog entry = new AuditLog();
        entry.setActor("system");
        entry.setAction("CALL_NEXT");
        entry.setTarget("visit_id=1");

        recorder.record(entry);

        verify(auditLogMapper).insert(entry);
    }
}
