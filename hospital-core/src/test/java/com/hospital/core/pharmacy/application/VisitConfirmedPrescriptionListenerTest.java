package com.hospital.core.pharmacy.application;

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
 * R-64: 就诊单"医嘱已锁定"事件 → 生成处方。
 *
 * <p>与 {@code VisitConfirmedLabListenerTest} 同构,关注点一致:无药品医嘱不动作、
 * 有则透传、成功补审计、失败既不外抛也不写审计。
 */
@ExtendWith(MockitoExtension.class)
class VisitConfirmedPrescriptionListenerTest {

    @Mock
    PrescriptionService prescriptionService;
    @Mock
    AuditRecorder auditRecorder;

    VisitConfirmedPrescriptionListener listener;

    @BeforeEach
    void setUp() {
        listener = new VisitConfirmedPrescriptionListener(prescriptionService, auditRecorder);
    }

    private VisitOrdersConfirmedEvent event(List<Long> labOrderIds, List<Long> medOrderIds, Trigger trigger) {
        return new VisitOrdersConfirmedEvent(10L, 42L, 7L, labOrderIds, medOrderIds, trigger);
    }

    @Test
    @DisplayName("没有药品医嘱 → 不调用 service、不写审计(只有检验医嘱时处方侧必须彻底不动作)")
    void noMedicationOrders_skips() {
        listener.onVisitOrdersConfirmed(event(List.of(100L), List.of(), Trigger.CONFIRM));

        verify(prescriptionService, never()).createFromVisit(any(), any());
        verify(auditRecorder, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("药品桶为 null → 不调用 service(防御性)")
    void nullMedicationOrders_skips() {
        listener.onVisitOrdersConfirmed(event(List.of(100L), null, Trigger.CONFIRM));

        verify(prescriptionService, never()).createFromVisit(any(), any());
    }

    @Test
    @DisplayName("有药品医嘱 → 调用 service(处方按就诊单+医生生成)")
    void medicationOrders_callsService() {
        listener.onVisitOrdersConfirmed(event(List.of(), List.of(21L, 22L), Trigger.CONFIRM));

        verify(prescriptionService).createFromVisit(10L, 7L);
    }

    @Test
    @DisplayName("成功 → 补写 CREATE_PRESCRIPTION 审计,明细含触发点")
    void success_writesAudit() {
        listener.onVisitOrdersConfirmed(event(List.of(), List.of(21L), Trigger.CONFIRM));

        verify(auditRecorder).record(eq("CREATE_PRESCRIPTION"), eq("visit_id=10"),
                contains("确单"));
    }

    @Test
    @DisplayName("service 抛异常 → 就地吞掉且不写审计")
    void serviceThrows_swallowedAndNoAudit() {
        when(prescriptionService.createFromVisit(eq(10L), any()))
                .thenThrow(new IllegalStateException("模拟写库失败"));

        assertThatCode(() -> listener.onVisitOrdersConfirmed(event(List.of(), List.of(21L), Trigger.CONFIRM)))
                .doesNotThrowAnyException();
        verify(auditRecorder, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("service 抛 RuntimeException → 同样不外抛")
    void serviceThrowsRuntime_swallowed() {
        doThrow(new RuntimeException("模拟下游不可用"))
                .when(prescriptionService).createFromVisit(eq(10L), any());

        assertThatCode(() -> listener.onVisitOrdersConfirmed(event(List.of(), List.of(21L), Trigger.CONFIRM)))
                .doesNotThrowAnyException();
    }
}
