package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;

@ExtendWith(MockitoExtension.class)
class VisitServiceTest {

    @Mock VisitMapper visitMapper;
    @Mock OrderMapper orderMapper;
    @Mock ChargeMapper chargeMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PatientService patientService;
    @Mock StaffService staffService;
    @Mock DepartmentService departmentService;
    @Mock VisitReadModelService readModelService;
    @Mock VisitReadModelMapper readModelMapper;

    VisitService service;

    @BeforeEach
    void setUp() {
        service = new VisitService(visitMapper, orderMapper, chargeMapper,
                eventPublisher, patientService, staffService, departmentService,
                readModelService, readModelMapper);
    }

    private static Visit visit(Long id, String status) {
        Visit v = new Visit();
        v.setId(id);
        v.setPatientId(42L);
        v.setStatus(status);
        return v;
    }

    private static Order order(Long id, Long visitId, String status) {
        Order o = new Order();
        o.setId(id);
        o.setVisitId(visitId);
        o.setStatus(status);
        return o;
    }

    private static Charge charge(Long id, Long visitId, Long orderId, String payStatus) {
        Charge c = new Charge();
        c.setId(id);
        c.setVisitId(visitId);
        c.setOrderId(orderId);
        c.setPayStatus(payStatus);
        return c;
    }

    @Nested
    @DisplayName("手动结束就诊 finishVisit")
    class FinishVisit {

        @Test
        @DisplayName("就诊不存在 → IllegalArgumentException")
        void notFound() {
            when(visitMapper.selectById(1L)).thenReturn(null);

            assertThatThrownBy(() -> service.finishVisit(1L, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("就诊不存在");
        }

        @Test
        @DisplayName("草稿(CREATED)状态 → 拒绝结束")
        void wrongStatus() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));

            assertThatThrownBy(() -> service.finishVisit(1L, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("仅已确单/进行中");
        }

        @Test
        @DisplayName("存在未缴(UNPAID)费用 → 拒绝结束")
        void hasUnpaid() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            when(chargeMapper.selectList(null)).thenReturn(List.of(charge(9L, 1L, 5L, "UNPAID")));

            assertThatThrownBy(() -> service.finishVisit(1L, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未缴费用");
        }

        @Test
        @DisplayName("存在未执行(CREATED)医嘱且 force=false → 保留医嘱并正常结束")
        void hasPendingOrders() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            when(chargeMapper.selectList(null)).thenReturn(List.of());
            Order pending = order(5L, 1L, "CREATED");
            when(orderMapper.selectList(any())).thenReturn(List.of(pending));

            VisitDetail result = service.finishVisit(1L, false);

            assertThat(result.getVisit().getStatus()).isEqualTo("FINISHED");
            assertThat(pending.getStatus()).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("存在未执行医嘱但 force=true → 作废医嘱后正常结束")
        void forceCancelPendingOrders() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            when(chargeMapper.selectList(null)).thenReturn(List.of());
            when(orderMapper.selectList(any())).thenReturn(List.of(order(5L, 1L, "CREATED")));

            VisitDetail result = service.finishVisit(1L, true);

            assertThat(result.getVisit().getStatus()).isEqualTo("FINISHED");
        }
    }

    @Nested
    @DisplayName("退费 refundOrder")
    class RefundOrder {

        @Test
        @DisplayName("医嘱不存在或不属于该就诊 → IllegalArgumentException")
        void notFound() {
            when(orderMapper.selectById(5L)).thenReturn(null);

            assertThatThrownBy(() -> service.refundOrder(1L, 5L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("医嘱不存在");
        }

        @Test
        @DisplayName("已执行(EXECUTED)医嘱 → 不可退费")
        void alreadyExecuted() {
            when(orderMapper.selectById(5L)).thenReturn(order(5L, 1L, "EXECUTED"));

            assertThatThrownBy(() -> service.refundOrder(1L, 5L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("只能退费未执行");
        }
    }
}
