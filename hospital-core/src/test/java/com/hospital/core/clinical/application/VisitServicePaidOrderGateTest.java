package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * 「已收费的医嘱不可直接修改 / 取消」闸门专项测试（纯 Mockito，不启 Spring 上下文）。
 *
 * <p>背景：{@code editOrder} / {@code cancelOrder} 历史上只按医嘱自身 {@code status==CREATED} 判断，
 * 完全不看收费行 —— 于是能改掉 / 作废一条已 PAID 的医嘱，而 {@code cancelOrder} 只删 UNPAID 行，
 * PAID 行会作为孤儿留在账上（钱收了、医嘱却没了）。正确路径是先 {@code refundOrder}。
 *
 * <p>覆盖两面：① 存在 PAID 行时两条路径都必须拒绝且不产生任何写；② 无 PAID 行时必须照常放行
 * （防"闸门误伤一切"的假绿），并反向确认退费路径不受影响。
 */
@ExtendWith(MockitoExtension.class)
class VisitServicePaidOrderGateTest {

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

    private static final Long VISIT_ID = 100L;
    private static final Long ORDER_ID = 200L;

    @BeforeAll
    static void initMybatisPlus() {
        // 闸门用 LambdaQueryWrapper.eq(Charge::getPayStatus, ...) 解析列名，纯单测需先初始化 lambda 缓存
        MybatisPlusTestSupport.initLambdaCache(Charge.class, Order.class);
    }

    @BeforeEach
    void setUp() {
        service = new VisitService(visitMapper, orderMapper, chargeMapper,
                eventPublisher, patientService, staffService, departmentService,
                readModelService, readModelMapper);
    }

    private static Order order(String status) {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setVisitId(VISIT_ID);
        o.setType("LAB");
        o.setItemName("血常规");
        o.setQuantity(1);
        o.setUnitPrice(new BigDecimal("30.00"));
        o.setAmount(new BigDecimal("30.00"));
        o.setStatus(status);
        return o;
    }

    private static Charge charge(String payStatus) {
        Charge c = new Charge();
        c.setId(300L);
        c.setVisitId(VISIT_ID);
        c.setOrderId(ORDER_ID);
        c.setItemName("血常规");
        c.setAmount(new BigDecimal("30.00"));
        c.setPayStatus(payStatus);
        return c;
    }

    /**
     * 过了闸门之后的收尾链路会走 {@code buildDetailReusingReadModel}，需要读模型非 null 才不会退回
     * {@code getDetail}（那会再查一遍并依赖更多 mock）。
     * 两个 selectList 不显式打桩：Mockito 对 List 返回值默认给空集合，正是这里想要的"无关联行"。
     */
    private void stubDetailTail() {
        when(readModelService.refresh(VISIT_ID)).thenReturn(new VisitReadModel());
        when(visitMapper.selectById(VISIT_ID)).thenReturn(new Visit());
    }

    @Nested
    @DisplayName("存在已收费行 → 拒绝，且不产生任何写")
    class WhenPaid {

        @Test
        @DisplayName("editOrder 抛「已收费…请先退费」，医嘱与收费行都没被动过")
        void editOrderBlocked() {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(order("CREATED"));
            when(chargeMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.editOrder(VISIT_ID, ORDER_ID, order("CREATED")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已收费")
                    .hasMessageContaining("退费");

            verify(orderMapper, never()).updateById(any(Order.class));
            verify(chargeMapper, never()).updateById(any(Charge.class));
            verify(chargeMapper, never()).delete(any());
        }

        @Test
        @DisplayName("cancelOrder 同样被拦：医嘱不置 CANCELLED、UNPAID 行也不删")
        void cancelOrderBlocked() {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(order("CREATED"));
            when(chargeMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.cancelOrder(VISIT_ID, ORDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已收费")
                    .hasMessageContaining("取消");

            verify(orderMapper, never()).updateById(any(Order.class));
            verify(chargeMapper, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("无已收费行 → 闸门不得误伤")
    class WhenNotPaid {

        @Test
        @DisplayName("只有 UNPAID 行时 editOrder 正常落库")
        void editOrderPasses() {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(order("CREATED"));
            when(chargeMapper.selectCount(any())).thenReturn(0L);
            stubDetailTail();

            Order updates = order("CREATED");
            updates.setItemName("尿常规");
            updates.setUnitPrice(new BigDecimal("20.00"));

            assertThatCode(() -> service.editOrder(VISIT_ID, ORDER_ID, updates)).doesNotThrowAnyException();

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderMapper).updateById(captor.capture());
            assertThat(captor.getValue().getItemName()).isEqualTo("尿常规");
            assertThat(captor.getValue().getAmount()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("尚未产生收费行时 cancelOrder 正常作废医嘱")
        void cancelOrderPasses() {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(order("CREATED"));
            when(chargeMapper.selectCount(any())).thenReturn(0L);
            stubDetailTail();

            service.cancelOrder(VISIT_ID, ORDER_ID);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("CANCELLED");
            verify(chargeMapper).delete(any());
        }

        @Test
        @DisplayName("refundOrder 不经过闸门：已收费正是它要处理的输入，且 PAID 行置 REFUNDED 不删除")
        void refundPathUnaffected() {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(order("CREATED"));
            when(chargeMapper.selectList(any())).thenReturn(List.of(charge("PAID")));
            stubDetailTail();

            service.refundOrder(VISIT_ID, ORDER_ID);

            ArgumentCaptor<Charge> captor = ArgumentCaptor.forClass(Charge.class);
            verify(chargeMapper).updateById(captor.capture());
            assertThat(captor.getValue().getPayStatus()).isEqualTo("REFUNDED");
            verify(chargeMapper, never()).delete(any());
        }
    }
}
