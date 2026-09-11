package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.domain.VisitStatusEvent;
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

    @BeforeAll
    static void initMybatisPlus() {
        // R-05: 批量 UPDATE 用了 LambdaUpdateWrapper,set() 会即时解析列名,
        // 纯单测没有 Spring 上下文,需手动初始化 lambda 缓存(生产由 mapper 注册时自动初始化)
        MybatisPlusTestSupport.initLambdaCache(Charge.class, Order.class);
    }

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
            // R-05: finishVisit 的未缴判断已由 selectList(null) 全表扫描改为 selectCount 下推,桩同步调整
            when(chargeMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.finishVisit(1L, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未缴费用");
        }

        @Test
        @DisplayName("存在未执行(CREATED)医嘱且 force=false → 保留医嘱并正常结束")
        void hasPendingOrders() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            when(chargeMapper.selectCount(any())).thenReturn(0L);
            Order pending = order(5L, 1L, "CREATED");
            when(orderMapper.selectList(any())).thenReturn(List.of(pending));

            VisitDetail result = service.finishVisit(1L, false);

            assertThat(result.getVisit().getStatus()).isEqualTo("FINISHED");
            // R-05: 批量作废改为单条 UPDATE,force=false 时不应对医嘱发起任何 UPDATE
            verify(orderMapper, never()).update(any(), any());
            assertThat(pending.getStatus()).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("存在未执行医嘱但 force=true → 作废医嘱后正常结束")
        void forceCancelPendingOrders() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            when(chargeMapper.selectCount(any())).thenReturn(0L);
            when(orderMapper.selectList(any())).thenReturn(List.of(order(5L, 1L, "CREATED")));

            VisitDetail result = service.finishVisit(1L, true);

            assertThat(result.getVisit().getStatus()).isEqualTo("FINISHED");
            // R-05: 医嘱与收费作废均为单条批量 UPDATE(不再逐条 updateById)
            verify(orderMapper).update(isNull(), any(LambdaUpdateWrapper.class));
            verify(chargeMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        }

        /**
         * R-53: 此前只断言最终状态为 FINISHED,未验证 force=true 的<b>退款副作用</b>。
         * 实现:对被作废医嘱名下 pay_status=PAID 的收费,单条批量 UPDATE 置 REFUNDED 并回填 refund_time。
         */
        @Test
        @DisplayName("R-53 force=true:已缴费(PAID)收费被置 REFUNDED 且回填 refund_time")
        void finishVisit_forceWithPaidCharge_setsRefundedAndRefundTime() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            // 无未缴费用 → 允许结束(force 退款分支才会被执行)
            when(chargeMapper.selectCount(any())).thenReturn(0L);
            when(orderMapper.selectList(any())).thenReturn(List.of(order(5L, 1L, "CREATED")));

            VisitDetail result = service.finishVisit(1L, true);

            assertThat(result.getVisit().getStatus()).isEqualTo("FINISHED");

            // 医嘱作废(CREATED → CANCELLED)是单条批量 UPDATE
            verify(orderMapper).update(isNull(), any(LambdaUpdateWrapper.class));

            // 收费退费(PAID → REFUNDED + refund_time)是单条批量 UPDATE,校验 SET / WHERE 语义
            ArgumentCaptor<LambdaUpdateWrapper<Charge>> refundCaptor =
                    ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(chargeMapper).update(isNull(), refundCaptor.capture());
            LambdaUpdateWrapper<Charge> refund = refundCaptor.getValue();
            // SET 参数在 set() 时即时登记
            assertThat(refund.getSqlSet()).contains("pay_status").contains("refund_time");
            // 注意:MP 的 WHERE 参数是惰性的,首次生成 SQL 片段(getTargetSql/getSqlSegment)时才登记进
            // paramNameValuePairs —— 故必须先访问 SQL 片段,再断言 WHERE 参数值(与 R-05 lifecycle 测试同一顺序)。
            assertThat(refund.getTargetSql()).contains("pay_status").contains("visit_id");
            assertThat(refund.getParamNameValuePairs().values())
                    .contains("REFUNDED")   // SET pay_status = 'REFUNDED'
                    .contains("PAID");      // WHERE pay_status = 'PAID'(仅退已缴费的)
            // refund_time 回填为当前时间(LocalDateTime)
            assertThat(refund.getParamNameValuePairs().values())
                    .anyMatch(v -> v instanceof LocalDateTime);
        }

        /**
         * R-53 实测行为(与用例名"删除未缴"的措辞不符,以实际实现为准):
         * {@code finishVisit} 在 force 分支<b>之前</b>就有"存在未缴费用即拒绝"的守卫,
         * 因此存在 UNPAID 收费时会直接抛 IllegalStateException,<b>既不会删除未缴收费、也不会退费、也不会作废医嘱</b>。
         * (实现中"删除 UNPAID"只发生在 {@code refundOrder},不在 {@code finishVisit}。)
         */
        @Test
        @DisplayName("R-53 实测:存在未缴(UNPAID)费用时 force 结束被守卫拒绝,既不删除也不退费")
        void finishVisit_forceWithUnpaidCharge_deletesUnpaidNotRefund() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            // R-05: 未缴判断改用 selectCount 下推;返回 1 表示存在未缴
            when(chargeMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.finishVisit(1L, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未缴费用");

            // 守卫先于 force 分支:未缴收费既未被物理删除,也未被退费
            verify(chargeMapper, never()).delete(any());
            verify(chargeMapper, never()).update(any(), any());
            // 医嘱不应被作废
            verify(orderMapper, never()).update(any(), any());
            // 就诊状态不应被推进为 FINISHED
            verify(visitMapper, never()).updateById(any(Visit.class));
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

    /**
     * R-53: {@code pay} 的药房通知副作用此前完全未 verify。
     * 实现:缴费后,若存在待执行(CREATED)的药品(MEDICATION)医嘱,则发布
     * {@link VisitStatusEvent}(status=PAID),驱动药房发药;无药品医嘱则不发。
     */
    @Nested
    @DisplayName("结算 pay 的事件副作用")
    class Pay {

        @Test
        @DisplayName("R-53 pay:存在待执行药品医嘱 → 发布 VisitStatusEvent(PAID)")
        void pay_hasMedication_publishesVisitStatusEvent() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            // R-05: 是否存在待执行药品医嘱改为 selectCount 下推
            when(orderMapper.selectCount(any())).thenReturn(1L);
            // getDetail 内部查询
            when(orderMapper.selectList(any())).thenReturn(List.of());
            when(chargeMapper.selectList(any())).thenReturn(List.of());

            service.pay(1L);

            ArgumentCaptor<VisitStatusEvent> eventCaptor = ArgumentCaptor.forClass(VisitStatusEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            VisitStatusEvent event = eventCaptor.getValue();
            assertThat(event.visitId()).isEqualTo(1L);
            assertThat(event.patientId()).isEqualTo(42L);
            assertThat(event.status()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("R-53 pay:无药品医嘱 → 不发布 VisitStatusEvent")
        void pay_noMedication_doesNotPublish() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            when(orderMapper.selectCount(any())).thenReturn(0L);
            when(orderMapper.selectList(any())).thenReturn(List.of());
            when(chargeMapper.selectList(any())).thenReturn(List.of());

            service.pay(1L);

            verify(eventPublisher, never()).publishEvent(any());
        }
    }
}
