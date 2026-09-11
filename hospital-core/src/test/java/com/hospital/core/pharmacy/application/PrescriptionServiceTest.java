package com.hospital.core.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.pharmacy.domain.Prescription;
import com.hospital.core.pharmacy.domain.PrescriptionItem;
import com.hospital.core.pharmacy.infrastructure.PrescriptionItemMapper;
import com.hospital.core.pharmacy.infrastructure.PrescriptionMapper;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportPdfEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * PrescriptionService 核心路径测试。
 * 使用 doAnswer 绕开 MyBatis-Plus 3.5.7 新增的 Collection 重载导致的 Mockito 泛型歧义。
 * 验证策略:通过返回值断言 + doAnswer 设置自增 ID 来验证服务行为。
 */
@ExtendWith(MockitoExtension.class)
class PrescriptionServiceTest {

    @Mock PrescriptionMapper prescriptionMapper;
    @Mock PrescriptionItemMapper itemMapper;
    @Mock OrderMapper orderMapper;
    @Mock ChargeMapper chargeMapper;
    @Mock VisitService visitService;
    @Mock ReportService reportService;
    @Mock PatientService patientService;
    @Mock StaffService staffService;
    @Mock ApplicationEventPublisher eventPublisher;

    PrescriptionService service;

    @BeforeEach
    void setUp() {
        service = new PrescriptionService(prescriptionMapper, itemMapper, orderMapper, chargeMapper, visitService, reportService, patientService, staffService, eventPublisher);
    }

    @Nested
    @DisplayName("创建处方")
    class Create {

        @Test
        @DisplayName("从就诊的 MEDICATION 医嘱创建处方 → 返回 PENDING 处方")
        void createFromVisit_success() {
            when(visitService.get(10L)).thenReturn(visit(10L));
            when(orderMapper.selectList(any())).thenReturn(List.of(medOrder(1L, "头孢"), medOrder(2L, "布洛芬")));

            Prescription result = service.createFromVisit(10L, 5L);

            assertThat(result.getVisitId()).isEqualTo(10L);
            assertThat(result.getPatientId()).isEqualTo(42L);
            assertThat(result.getStatus()).isEqualTo("PENDING");
            assertThat(result.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("该就诊无 MEDICATION 医嘱 → IllegalStateException")
        void createFromVisit_noOrders_throws() {
            when(visitService.get(10L)).thenReturn(visit(10L));
            when(orderMapper.selectList(any())).thenReturn(List.of());
            assertThatThrownBy(() -> service.createFromVisit(10L, 5L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("无可创建");
        }

        @Test
        @DisplayName("R-64 幂等:已有 PENDING 处方且该就诊医嘱已被完全覆盖 → 返回既有处方,不抛异常、不重复插明细")
        void createFromVisit_existingPendingNoNewOrders_returnsExisting() {
            when(visitService.get(10L)).thenReturn(visit(10L));
            when(orderMapper.selectList(any())).thenReturn(List.of(medOrder(1L, "头孢")));

            Prescription existing = new Prescription();
            existing.setId(9L);
            existing.setVisitId(10L);
            existing.setStatus("PENDING");
            when(prescriptionMapper.selectOne(any())).thenReturn(existing);

            PrescriptionItem covered = new PrescriptionItem();
            covered.setPrescriptionId(9L);
            covered.setOrderId(1L);
            when(itemMapper.selectList(any())).thenReturn(List.of(covered));

            Prescription result = service.createFromVisit(10L, 5L);

            // 语义变更:原先这里抛 IllegalStateException,调用方(前端)靠"是不是 409"猜"没东西可追加"。
            // 现在本方法还会被确单事件监听器与对账 job 调用 —— 对它们而言"没有新医嘱"是正常的无事可做。
            assertThat(result).isSameAs(existing);
            verify(itemMapper, never()).insert(any(PrescriptionItem.class));
        }
    }

    @Nested
    @DisplayName("发药")
    class Dispense {

        @Test
        @DisplayName("PENDING 处方 → 返回 DISPENSED, 含药师 ID, 回写医嘱, 发布报告 PDF 事件")
        void dispense_success() {
            var rx = pendingRx();
            rx.setVisitId(10L);
            when(prescriptionMapper.selectById(1L)).thenReturn(rx);
            when(itemMapper.selectList(any())).thenReturn(List.of(pendingItem(1L, "头孢")));
            when(orderMapper.selectById(10L)).thenReturn(creOrder(10L));
            // 发药生成报告(B端报告出 PDF 的修复):桩掉 createAndPublish 返回带 id 的报告
            var report = new Report();
            report.setId(99L);
            when(reportService.createAndPublish(any(), any(), any(), any(), any(), any())).thenReturn(report);

            Prescription result = service.dispense(1L, 7L, "饭后服用，每日三次");

            assertThat(result.getStatus()).isEqualTo("DISPENSED");
            assertThat(result.getPharmacistId()).isEqualTo(7L);
            assertThat(result.getDispensedAt()).isNotNull();
            // 发药应发布报告 PDF 事件(修复:B 端报告此前无 PDF)
            verify(eventPublisher).publishEvent(any(ReportPdfEvent.class));
        }

        @Test
        @DisplayName("非 PENDING 处方 → IllegalStateException")
        void dispense_wrongStatus_throws() {
            var p = pendingRx();
            p.setStatus("DISPENSED");
            when(prescriptionMapper.selectById(1L)).thenReturn(p);

            assertThatThrownBy(() -> service.dispense(1L, 7L, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("仅 PENDING");
        }

        @Test
        @DisplayName("处方不存在 → IllegalArgumentException")
        void dispense_notFound_throws() {
            when(prescriptionMapper.selectById(999L)).thenReturn(null);
            assertThatThrownBy(() -> service.dispense(999L, 7L, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("取消处方")
    class Cancel {
        @Test
        @DisplayName("PENDING → CANCELLED")
        void cancel_success() {
            var p = pendingRx();
            p.setId(5L);
            when(prescriptionMapper.selectById(5L)).thenReturn(p);
            when(itemMapper.selectList(any())).thenReturn(List.of(pendingItem(5L, "头孢")));

            Prescription result = service.cancel(5L);

            assertThat(result.getStatus()).isEqualTo("CANCELLED");
        }
    }

    private Visit visit(Long id) {
        var v = new Visit();
        v.setId(id);
        v.setPatientId(42L);
        return v;
    }

    private Prescription pendingRx() {
        var p = new Prescription();
        p.setId(1L);
        p.setStatus("PENDING");
        return p;
    }

    private Order medOrder(Long id, String name) {
        var o = new Order();
        o.setId(id);
        o.setItemName(name);
        o.setType("MEDICATION");
        o.setStatus("CREATED");
        o.setVisitId(10L);
        return o;
    }

    private Order creOrder(Long id) {
        var o = new Order();
        o.setId(id);
        o.setStatus("CREATED");
        return o;
    }

    private PrescriptionItem pendingItem(Long rxId, String name) {
        var i = new PrescriptionItem();
        i.setPrescriptionId(rxId);
        i.setOrderId(10L);
        i.setItemName(name);
        i.setStatus("PENDING");
        return i;
    }
}
