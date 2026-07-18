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

    LabService service;

    @BeforeEach
    void setUp() {
        service = new LabService(requisitionMapper, resultItemMapper, orderMapper, visitMapper, visitService, chargeService, reportService, eventPublisher);
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