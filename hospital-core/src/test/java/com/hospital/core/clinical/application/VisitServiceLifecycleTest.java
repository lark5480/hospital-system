package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;

/**
 * VisitService 状态机生命周期测试:confirm + pay 核心路径。
 */
@ExtendWith(MockitoExtension.class)
class VisitServiceLifecycleTest {

    @Mock VisitMapper visitMapper;
    @Mock OrderMapper orderMapper;
    @Mock ChargeMapper chargeMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PatientService patientService;
    @Mock StaffService staffService;
    @Mock DepartmentService departmentService;
    @Mock VisitReadModelService readModelService;
    @Mock VisitReadModelMapper readModelMapper;

    @Captor ArgumentCaptor<Visit> visitCaptor;

    VisitService service;

    @BeforeEach
    void setUp() {
        service = new VisitService(visitMapper, orderMapper, chargeMapper,
                eventPublisher, patientService, staffService, departmentService,
                readModelService, readModelMapper);
    }

    private static Visit visit(Long id, Long patientId, Long deptId, Long doctorId, String status) {
        Visit v = new Visit();
        v.setId(id);
        v.setPatientId(patientId);
        v.setDeptId(deptId);
        v.setDoctorId(doctorId);
        v.setStatus(status);
        return v;
    }

    private static Charge charge(Long id, Long visitId, Long orderId, String payStatus) {
        Charge c = new Charge();
        c.setId(id);
        c.setVisitId(visitId);
        c.setOrderId(orderId);
        c.setAmount(new BigDecimal("100.00"));
        c.setPayStatus(payStatus);
        return c;
    }

    private static VisitDetail mockDetail(Visit visit) {
        return VisitDetail.builder()
                .visit(visit)
                .orders(List.of())
                .charges(List.of())
                .totalAmount(BigDecimal.ZERO)
                .build();
    }

    @Nested
    @DisplayName("确单 confirm")
    class Confirm {

        @Test
        @DisplayName("确单成功:CREATED → CONFIRMED")
        void confirm_success() {
            Visit created = visit(1L, 42L, 7L, 10L, "CREATED");
            when(visitMapper.selectById(1L)).thenReturn(created);
            // getDetail 内部会再查 visit
            when(visitMapper.selectById(1L)).thenReturn(created);

            VisitDetail result = service.confirm(1L);

            verify(visitMapper).updateById(visitCaptor.capture());
            Visit updated = visitCaptor.getValue();
            assertThat(updated.getStatus()).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("确单幂等:已 CONFIRMED 直接返回,不重复更新")
        void confirm_idempotent() {
            Visit confirmed = visit(1L, 42L, 7L, 10L, "CONFIRMED");
            when(visitMapper.selectById(1L)).thenReturn(confirmed);
            // getDetail 需要返回
            when(orderMapper.selectList(any())).thenReturn(List.of());
            when(chargeMapper.selectList(any())).thenReturn(List.of());

            VisitDetail result = service.confirm(1L);

            // 不应调用 updateById(幂等直接返回)
            verify(visitMapper, never()).updateById(any(Visit.class));
        }

        @Test
        @DisplayName("就诊不存在 → IllegalArgumentException")
        void confirm_notFound_throws() {
            when(visitMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> service.confirm(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("就诊不存在");
        }

        @Test
        @DisplayName("非 CREATED 状态(如 IN_PROGRESS) → 非法状态转换")
        void confirm_wrongStatus_throws() {
            Visit inProgress = visit(1L, 42L, 7L, 10L, "IN_PROGRESS");
            when(visitMapper.selectById(1L)).thenReturn(inProgress);

            // IN_PROGRESS 不允许转换到 CONFIRMED
            assertThatThrownBy(() -> service.confirm(1L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("结算 pay")
    class Pay {

        @Test
        @DisplayName("CREATED 状态就诊单不可结算 → IllegalStateException")
        void pay_created_throws() {
            Visit created = visit(1L, 42L, 7L, 10L, "CREATED");
            when(visitMapper.selectById(1L)).thenReturn(created);

            assertThatThrownBy(() -> service.pay(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("尚未确单");
        }

        @Test
        @DisplayName("就诊不存在 → IllegalArgumentException")
        void pay_notFound_throws() {
            when(visitMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> service.pay(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("就诊不存在");
        }

        @Test
        @DisplayName("CONFIRMED 有未缴费用 → 缴费后推进为 IN_PROGRESS")
        void pay_confirmedWithUnpaid_transitionsToInProgress() {
            Visit confirmed = visit(1L, 42L, 7L, 10L, "CONFIRMED");
            when(visitMapper.selectById(1L)).thenReturn(confirmed);
            // getDetail 内部 selectList(wrapper) 返回空
            when(chargeMapper.selectList(any())).thenReturn(List.of());
            // pay 内部 selectList(null) 返回未缴记录(后定义优先匹配 null)
            when(chargeMapper.selectList(null)).thenReturn(List.of(
                    charge(1L, 1L, 1L, "UNPAID")
            ));
            when(orderMapper.selectList(any())).thenReturn(List.of());

            VisitDetail result = service.pay(1L);

            // 验证收费记录被更新为 PAID
            ArgumentCaptor<Charge> chargeCaptor = ArgumentCaptor.forClass(Charge.class);
            verify(chargeMapper).updateById(chargeCaptor.capture());
            assertThat(chargeCaptor.getValue().getPayStatus()).isEqualTo("PAID");
            assertThat(chargeCaptor.getValue().getPayTime()).isNotNull();

            // 验证就诊单推进为 IN_PROGRESS
            verify(visitMapper).updateById(visitCaptor.capture());
            assertThat(visitCaptor.getValue().getStatus()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("IN_PROGRESS 有未缴费用 → 缴费后状态保持 IN_PROGRESS")
        void pay_inProgressWithUnpaid_staysInProgress() {
            Visit inProgress = visit(1L, 42L, 7L, 10L, "IN_PROGRESS");
            when(visitMapper.selectById(1L)).thenReturn(inProgress);
            // getDetail 内部 selectList(wrapper) 返回空
            when(chargeMapper.selectList(any())).thenReturn(List.of());
            // pay 内部 selectList(null) 返回未缴记录(后定义优先匹配 null)
            when(chargeMapper.selectList(null)).thenReturn(List.of(
                    charge(1L, 1L, 1L, "UNPAID")
            ));
            when(orderMapper.selectList(any())).thenReturn(List.of());

            VisitDetail result = service.pay(1L);

            // 缴费完成
            ArgumentCaptor<Charge> chargeCaptor = ArgumentCaptor.forClass(Charge.class);
            verify(chargeMapper).updateById(chargeCaptor.capture());
            assertThat(chargeCaptor.getValue().getPayStatus()).isEqualTo("PAID");

            // IN_PROGRESS 不应再触发状态转换
            verify(visitMapper, never()).updateById(any(Visit.class));
        }
    }
}
