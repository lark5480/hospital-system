package com.hospital.core.lab.application;

import com.hospital.core.clinical.application.ChargeService;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportPdfEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.hospital.core.lab.domain.LabRequisition;
import com.hospital.core.lab.domain.LabResultItem;
import com.hospital.core.lab.infrastructure.LabRequisitionMapper;
import com.hospital.core.lab.infrastructure.LabResultItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabServiceTest {

    @Mock LabRequisitionMapper requisitionMapper;
    @Mock LabResultItemMapper resultItemMapper;
    @Mock OrderMapper orderMapper;
    @Mock VisitMapper visitMapper;
    @Mock VisitService visitService;
    @Mock ChargeService chargeService;
    @Mock ReportService reportService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock com.hospital.core.patient.application.PatientService patientService;
    @Mock com.hospital.core.org.application.StaffService staffService;

    LabService service;

    @BeforeEach
    void setUp() {
        service = new LabService(requisitionMapper, resultItemMapper, orderMapper, visitMapper, visitService, chargeService, reportService, eventPublisher, patientService, staffService);
    }

    @Nested
    @DisplayName("创建检验申请")
    class Create {

        @Test
        @DisplayName("从 LAB 医嘱创建申请 → 返回 PENDING 申请, patientId 自动回填")
        void createFromVisit_success() {
            when(visitMapper.selectById(10L)).thenReturn(visit(10L, 42L));
            when(orderMapper.selectList(any())).thenReturn(List.of(labOrder(1L, "血常规"), labOrder(2L, "肝功能")));

            LabRequisition result = service.createFromVisit(10L, 5L, null);

            assertThat(result.getVisitId()).isEqualTo(10L);
            assertThat(result.getPatientId()).isEqualTo(42L);
            assertThat(result.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("就诊无 LAB 医嘱 → IllegalStateException")
        void createFromVisit_noOrders_throws() {
            when(visitMapper.selectById(10L)).thenReturn(visit(10L, 42L));
            when(orderMapper.selectList(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.createFromVisit(10L, 5L, null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("就诊不存在 → IllegalArgumentException")
        void createFromVisit_visitNotFound_throws() {
            when(visitMapper.selectById(999L)).thenReturn(null);
            assertThatThrownBy(() -> service.createFromVisit(999L, 5L, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("幂等确单:就诊已确单/进行中 → 不得再调 confirm(否则撞状态机,事务整体回滚)")
        void createFromVisit_visitAlreadyConfirmed_doesNotConfirmAgain() {
            Visit confirmed = visit(10L, 42L);
            confirmed.setStatus("CONFIRMED");
            when(visitMapper.selectById(10L)).thenReturn(confirmed);
            when(orderMapper.selectList(any())).thenReturn(List.of(labOrder(1L, "血常规")));

            LabRequisition result = service.createFromVisit(10L, 5L, null);

            assertThat(result.getStatus()).isEqualTo("PENDING");
            // 关键断言:前端「确单」流程已经先调过 visitService.confirm(),
            // 此处再无条件确一次会抛「非法就诊状态转换: CONFIRMED → CONFIRMED」,
            // 异常导致整个事务回滚 —— 检验申请再也建不出来(曾经的真实故障)。
            verify(visitService, never()).confirm(any());
        }

        @Test
        @DisplayName("就诊仍是草稿(CREATED) → 由本方法确单一次(保留原有的锁定语义)")
        void createFromVisit_draft_confirmCalledOnce() {
            Visit draft = visit(10L, 42L);
            draft.setStatus("CREATED");
            when(visitMapper.selectById(10L)).thenReturn(draft);
            when(orderMapper.selectList(any())).thenReturn(List.of(labOrder(1L, "血常规")));

            service.createFromVisit(10L, 5L, null);

            verify(visitService).confirm(10L);
        }

        @Test
        @DisplayName("R-64 幂等:已有 PENDING 申请且该就诊医嘱已被完全覆盖 → 返回既有申请,不抛异常、不重复插明细")
        void createFromVisit_existingPendingNoNewOrders_returnsExisting() {
            when(visitMapper.selectById(10L)).thenReturn(visit(10L, 42L));
            when(orderMapper.selectList(any())).thenReturn(List.of(labOrder(1L, "血常规")));

            LabRequisition existing = pendingReq(9L);
            when(requisitionMapper.selectOne(any())).thenReturn(existing);
            LabResultItem covered = new LabResultItem();
            covered.setRequisitionId(9L);
            covered.setOrderId(1L);
            when(resultItemMapper.selectList(any())).thenReturn(List.of(covered));

            LabRequisition result = service.createFromVisit(10L, 5L, null);

            // 语义变更:原先这里抛 IllegalStateException,调用方(前端)靠"是不是 409"猜"没东西可追加"。
            // 现在本方法还会被确单事件监听器与对账 job 调用 —— 对它们而言"没有新医嘱"是正常的无事可做。
            assertThat(result).isSameAs(existing);
            verify(resultItemMapper, never()).insert(any(LabResultItem.class));
        }
    }

    @Nested
    @DisplayName("录入结果")
    class SubmitResults {

        @Test
        @DisplayName("逐项录入 → 返回同申请, 结果值回填")
        void submitResults_success() {
            when(requisitionMapper.selectById(1L)).thenReturn(pendingReq(1L));
            when(resultItemMapper.selectById(1L)).thenReturn(pendingItem(1L, "血常规"));
            when(orderMapper.selectById(10L)).thenReturn(labOrder(10L, "血常规"));
            when(resultItemMapper.selectList(any())).thenReturn(List.of(completedItem(1L, "血常规")));
            // 录入检验生成报告(B端报告出 PDF 的修复):桩掉 createAndPublish 返回带 id 的报告
            var report = new Report();
            report.setId(99L);
            when(reportService.createAndPublish(any(), any(), any(), any(), any(), any())).thenReturn(report);

            var entry = new LabService.ResultEntry();
            entry.setItemId(1L);
            entry.setResultValue("5.2");
            entry.setUnit("×10^9/L");
            entry.setRefRange("3.5-9.5");
            entry.setAbnormalFlag("NORMAL");

            LabRequisition result = service.submitResults(1L, 7L, List.of(entry));

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getTechnicianId()).isEqualTo(7L);
            assertThat(result.getReportedAt()).isNotNull();
            // 录入检验应发布报告 PDF 事件(修复:B 端报告此前无 PDF)
            verify(eventPublisher).publishEvent(any(ReportPdfEvent.class));
        }

        @Test
        @DisplayName("非 PENDING 申请 → IllegalStateException")
        void submitResults_wrongStatus_throws() {
            var r = pendingReq(1L);
            r.setStatus("EXECUTED");
            when(requisitionMapper.selectById(1L)).thenReturn(r);

            assertThatThrownBy(() -> service.submitResults(1L, 7L, List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("取消申请")
    class Cancel {
        @Test
        @DisplayName("PENDING → CANCELLED")
        void cancel_success() {
            when(requisitionMapper.selectById(1L)).thenReturn(pendingReq(1L));
            when(resultItemMapper.selectList(any())).thenReturn(List.of(pendingItem(1L, "血常规")));

            LabRequisition result = service.cancel(1L);

            assertThat(result.getStatus()).isEqualTo("CANCELLED");
        }
    }

    private Visit visit(Long id, Long patientId) {
        var v = new Visit();
        v.setId(id);
        v.setPatientId(patientId);
        return v;
    }

    private LabRequisition pendingReq(Long id) {
        var r = new LabRequisition();
        r.setId(id);
        r.setVisitId(1L);
        r.setStatus("PENDING");
        return r;
    }

    private LabResultItem pendingItem(Long id, String name) {
        var i = new LabResultItem();
        i.setId(id);
        i.setOrderId(10L);
        i.setItemName(name);
        i.setStatus("PENDING");
        return i;
    }

    private LabResultItem completedItem(Long id, String name) {
        var i = pendingItem(id, name);
        i.setStatus("COMPLETED");
        i.setResultValue("5.2");
        return i;
    }

    private Order labOrder(Long id, String name) {
        var o = new Order();
        o.setId(id);
        o.setItemName(name);
        o.setType("LAB");
        o.setStatus("CREATED");
        o.setVisitId(10L);
        return o;
    }
}