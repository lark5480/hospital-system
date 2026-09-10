package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.domain.VisitReadModel;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;

/**
 * R-06 金额计算校验与精度专项测试（纯 Mockito，不启 Spring 上下文）。
 *
 * <p>覆盖：单价为 null / 数量为负的非法入参拦截、零数量零金额、
 * 结果固定两位小数（HALF_UP）、汇总时跳过 null 金额（原实现 NPE）。</p>
 */
@ExtendWith(MockitoExtension.class)
class VisitServiceOrderAmountTest {

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
    VisitReadModelService readModelServiceUnderTest;

    @Captor ArgumentCaptor<VisitReadModel> readModelCaptor;

    @BeforeEach
    void setUp() {
        service = new VisitService(visitMapper, orderMapper, chargeMapper,
                eventPublisher, patientService, staffService, departmentService,
                readModelService, readModelMapper);
        // 读模型服务单独用真实实现（校验 totalAmount 汇总口径）
        readModelServiceUnderTest = new VisitReadModelService(readModelMapper, visitMapper,
                orderMapper, chargeMapper, patientService, staffService, departmentService);
    }

    private static Visit visit(Long id, String status) {
        Visit v = new Visit();
        v.setId(id);
        v.setPatientId(42L);
        v.setDeptId(7L);
        v.setStatus(status);
        return v;
    }

    private static Order order(String type, String itemName, Integer quantity, BigDecimal unitPrice) {
        Order o = new Order();
        o.setType(type);
        o.setItemName(itemName);
        o.setQuantity(quantity);
        o.setUnitPrice(unitPrice);
        return o;
    }

    private static Charge charge(Long id, Long visitId, Long orderId, BigDecimal amount, String payStatus) {
        Charge c = new Charge();
        c.setId(id);
        c.setVisitId(visitId);
        c.setOrderId(orderId);
        c.setAmount(amount);
        c.setPayStatus(payStatus);
        return c;
    }

    @Nested
    @DisplayName("R-06 金额入参校验")
    class AmountValidation {

        @Test
        @DisplayName("addOrder_unitPriceNull_throws: 单价为 null → 抛异常(原实现 NPE)")
        void addOrder_unitPriceNull_throws() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));
            Order order = order("MEDICATION", "阿莫西林", 2, null);

            assertThatThrownBy(() -> service.addOrder(1L, order))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("单价");
        }

        @Test
        @DisplayName("addOrder_negativeQuantity_throws: 数量为负 → 拒绝(防负额结算)")
        void addOrder_negativeQuantity_throws() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));
            Order order = order("MEDICATION", "阿莫西林", -1, new BigDecimal("10.00"));

            assertThatThrownBy(() -> service.addOrder(1L, order))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("数量");
        }

        @Test
        @DisplayName("单价为负 → 拒绝(防负额结算)")
        void addOrder_negativeUnitPrice_throws() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));
            Order order = order("MEDICATION", "阿莫西林", 2, new BigDecimal("-10.00"));

            assertThatThrownBy(() -> service.addOrder(1L, order))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("单价");
        }

        @Test
        @DisplayName("数量为 null → 拒绝(原实现 NPE)")
        void addOrder_nullQuantity_throws() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));
            Order order = order("MEDICATION", "阿莫西林", null, new BigDecimal("10.00"));

            assertThatThrownBy(() -> service.addOrder(1L, order))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("数量");
        }
    }

    @Nested
    @DisplayName("R-06 金额精度")
    class AmountScale {

        @Test
        @DisplayName("createWithOrders_zeroQuantity_zeroAmount: 数量 0 → 金额 0.00")
        void createWithOrders_zeroQuantity_zeroAmount() {
            Visit v = visit(1L, "CREATED");
            Order zero = order("MEDICATION", "阿莫西林", 0, new BigDecimal("15.50"));

            VisitDetail detail = service.createWithOrders(v, List.of(zero));

            assertThat(zero.getAmount()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(zero.getAmount().scale()).isEqualTo(2);
            assertThat(detail.getTotalAmount()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(detail.getTotalAmount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("createWithOrders_amountScaledToTwoDigits: 3.333 × 3 = 9.999 → 10.00")
        void createWithOrders_amountScaledToTwoDigits() {
            Visit v = visit(1L, "CREATED");
            Order o = order("MEDICATION", "阿莫西林", 3, new BigDecimal("3.333"));

            VisitDetail detail = service.createWithOrders(v, List.of(o));

            assertThat(o.getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(o.getAmount().scale()).isEqualTo(2);
            assertThat(detail.getTotalAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(detail.getTotalAmount().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("R-06 汇总金额")
    class TotalAmount {

        @Test
        @DisplayName("totalAmount_twoDigits: 读模型聚合下沉为一条 SQL 后金额保留两位小数")
        void totalAmount_nullAmountIgnored() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));
            // R-16: refresh 的 orders/charge 全量 selectList 已改为 VisitReadModelMapper.selectAggregate
            // 一条聚合 SQL(COALESCE(SUM(amount),0) 天然跳过金额为 null 的脏数据),
            // 故此处直接桩定聚合结果,校验写回读模型的金额/缴费状态口径。
            var agg = new VisitReadModelMapper.VisitAggregate();
            agg.setOrderCount(0);
            agg.setChargeCount(2);
            agg.setUnpaidCount(2);
            agg.setTotalAmount(new BigDecimal("10.51")); // 等价于 SUM(null, 10.505) → HALF_UP
            when(readModelMapper.selectAggregate(1L)).thenReturn(agg);
            when(readModelMapper.selectByVisitId(1L)).thenReturn(null);

            readModelServiceUnderTest.refresh(1L);

            verify(readModelMapper).insert(readModelCaptor.capture());
            BigDecimal total = readModelCaptor.getValue().getTotalAmount();
            assertThat(total).isEqualByComparingTo(new BigDecimal("10.51")); // 10.505 → HALF_UP
            assertThat(total.scale()).isEqualTo(2);
            assertThat(readModelCaptor.getValue().getPayStatus()).isEqualTo("HAS_UNPAID");
        }

        @Test
        @DisplayName("getDetail 汇总同样跳过 null 金额,不抛 NPE")
        void getDetail_nullAmountIgnored() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));
            when(chargeMapper.selectList(any())).thenReturn(List.of(
                    charge(1L, 1L, 1L, null, "UNPAID"),
                    charge(2L, 1L, 2L, new BigDecimal("10.00"), "PAID")
            ));

            VisitDetail detail = service.getDetail(1L);

            assertThat(detail.getTotalAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(detail.getPayStatus()).isEqualTo("HAS_UNPAID");
        }
    }
}
