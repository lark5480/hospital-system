package com.hospital.core.lab.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hospital.core.clinical.domain.VisitOrdersConfirmedEvent;
import com.hospital.core.clinical.domain.VisitOrdersConfirmedEvent.Trigger;
import com.hospital.core.platform.infrastructure.AuditRecorder;

/**
 * R-64: 就诊单"医嘱已锁定"事件 → 生成检验申请。
 *
 * <p>这是把"生成检验申请"从浏览器收回服务端后的接线测试,关注四件事:
 * <ol>
 *   <li>没有检验医嘱时<b>不动作</b>(避免为不该有的申请报错/写库);</li>
 *   <li>有检验医嘱时把<b>确切的医嘱 id 列表</b>透传给 service(而不是让它自己去猜范围);</li>
 *   <li>成功后<b>补写审计</b> —— 生成不再经 Controller,审计切面记不到账,
 *       不补写则"谁/何时/因何生成了检验申请"会从轨迹里消失;</li>
 *   <li>service 失败时<b>绝不向上抛</b>,且<b>不写审计</b>(不能出现"审计说建了、库里没有")。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VisitConfirmedLabListenerTest {

    @Mock
    LabService labService;
    @Mock
    AuditRecorder auditRecorder;

    VisitConfirmedLabListener listener;

    @BeforeEach
    void setUp() {
        listener = new VisitConfirmedLabListener(labService, auditRecorder);
    }

    private VisitOrdersConfirmedEvent event(List<Long> labOrderIds, List<Long> medOrderIds, Trigger trigger) {
        return new VisitOrdersConfirmedEvent(10L, 42L, 7L, labOrderIds, medOrderIds, trigger);
    }

    @Test
    @DisplayName("没有检验医嘱 → 不调用 service、不写审计(只有药品医嘱时检验侧必须彻底不动作)")
    void noLabOrders_skips() {
        listener.onVisitOrdersConfirmed(event(List.of(), List.of(200L), Trigger.CONFIRM));

        verify(labService, never()).createFromVisit(any(), any(), any());
        verify(auditRecorder, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("检验桶为 null → 不调用 service(防御性:事件可能被手工构造)")
    void nullLabOrders_skips() {
        listener.onVisitOrdersConfirmed(event(null, List.of(200L), Trigger.CONFIRM));

        verify(labService, never()).createFromVisit(any(), any(), any());
    }

    @Test
    @DisplayName("有检验医嘱 → 用事件里的确切 id 列表调用 service(不放大范围)")
    void labOrders_callsServiceWithExactIds() {
        listener.onVisitOrdersConfirmed(event(List.of(11L, 12L), List.of(), Trigger.CONFIRM));

        verify(labService).createFromVisit(10L, 7L, List.of(11L, 12L));
    }

    @Test
    @DisplayName("成功 → 补写 CREATE_REQUISITION 审计,明细含触发点与医嘱 id")
    void success_writesAudit() {
        listener.onVisitOrdersConfirmed(event(List.of(11L), List.of(), Trigger.CONFIRM));

        verify(auditRecorder).record(eq("CREATE_REQUISITION"), eq("visit_id=10"),
                contains("确单"));
    }

    @Test
    @DisplayName("确单后追加的触发点 → 审计明细能区分出来(便于回答'这张申请为什么存在')")
    void appendTrigger_auditDetailDistinguishes() {
        listener.onVisitOrdersConfirmed(event(List.of(11L), List.of(), Trigger.APPEND_ORDER));

        verify(auditRecorder).record(eq("CREATE_REQUISITION"), eq("visit_id=10"),
                contains("确单后追加医嘱"));
    }

    @Test
    @DisplayName("service 抛异常 → 就地吞掉且不写审计(绝不能出现'审计说建了、库里没有')")
    void serviceThrows_swallowedAndNoAudit() {
        when(labService.createFromVisit(eq(10L), any(), any()))
                .thenThrow(new IllegalStateException("模拟写库失败"));

        assertThatCode(() -> listener.onVisitOrdersConfirmed(event(List.of(11L), List.of(), Trigger.CONFIRM)))
                .doesNotThrowAnyException();
        verify(auditRecorder, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("service 抛 RuntimeException → 同样不外抛")
    void serviceThrowsRuntime_swallowed() {
        doThrow(new RuntimeException("模拟下游不可用"))
                .when(labService).createFromVisit(eq(10L), any(), any());

        assertThatCode(() -> listener.onVisitOrdersConfirmed(event(List.of(11L), List.of(), Trigger.CONFIRM)))
                .doesNotThrowAnyException();
    }
}
