package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.hospital.core.clinical.domain.VisitOrdersConfirmedEvent;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;

/**
 * R-64: {@link VisitOrdersConfirmedEvent} 的两个触发点。
 *
 * <p>背景:生成检验申请/处方原先由浏览器在确单后再发两个 HTTP 完成,属没有补偿的客户端编排
 * (请求丢失、被校验拦、中途失败都会让单据永久缺失,且连审计都不留痕)。
 * 现在改由服务端发布本事件、下游模块订阅生成,因此"事件在正确的时机、带正确的医嘱 id"是新的关键契约:
 * <ul>
 *   <li><b>确单</b> —— 把当时所有 {@code status=CREATED} 的医嘱按类型分桶,批量入事件;</li>
 *   <li><b>已确单后追加医嘱</b> —— 该医嘱即刻需要下游单据,单条入事件
 *       (缺了这条就会出现"确单后再追加检验医嘱 → 检验科永远看不到")。</li>
 * </ul>
 * 反向也要守住:草稿态追加医嘱<b>不能</b>发事件(草稿本就不生成下游单据,等确单时批量处理),
 * 否则会给下游制造大量"无从生成的空事件"。
 *
 * <p>注:断言用"捕获全部发布事件再按类型过滤",而不是靠 {@code ArgumentCaptor.forClass} 的类型过滤 ——
 * {@code addOrder} 同时还会发 {@code OrderCreatedEvent}(通知用),直接 {@code verify(...)} 会因次数不符而失败。
 */
@ExtendWith(MockitoExtension.class)
class VisitServiceDownstreamEventTest {

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
        MybatisPlusTestSupport.initLambdaCache(Charge.class, Order.class);
    }

    @BeforeEach
    void setUp() {
        service = new VisitService(visitMapper, orderMapper, chargeMapper,
                eventPublisher, patientService, staffService, departmentService,
                readModelService, readModelMapper);
    }

    /** 捕获全部发布的事件,按类型挑出 {@link VisitOrdersConfirmedEvent}(0 或 1 个)。 */
    private VisitOrdersConfirmedEvent captureDownstreamEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(VisitOrdersConfirmedEvent.class::isInstance)
                .map(VisitOrdersConfirmedEvent.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static Visit visit(Long id, String status) {
        Visit v = new Visit();
        v.setId(id);
        v.setPatientId(42L);
        v.setDoctorId(7L);
        v.setStatus(status);
        return v;
    }

    private static Order order(Long id, String type) {
        Order o = new Order();
        o.setId(id);
        o.setVisitId(1L);
        o.setType(type);
        o.setStatus("CREATED");
        o.setItemName("项目" + id);
        o.setUnitPrice(new java.math.BigDecimal("10.00"));
        o.setQuantity(1);
        return o;
    }

    @Nested
    @DisplayName("触发点一:确单 confirm")
    class Confirm {

        @Test
        @DisplayName("按类型分桶:LAB→检验桶、MEDICATION→药品桶,EXAM 不入桶")
        void confirm_bucketsByType() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));
            when(orderMapper.selectList(any())).thenReturn(List.of(
                    order(11L, "LAB"), order(12L, "MEDICATION"), order(13L, "EXAM")));
            when(chargeMapper.selectList(any())).thenReturn(List.of());

            service.confirm(1L);

            VisitOrdersConfirmedEvent event = captureDownstreamEvent();
            assertThat(event).isNotNull();
            assertThat(event.visitId()).isEqualTo(1L);
            assertThat(event.patientId()).isEqualTo(42L);
            assertThat(event.doctorId()).isEqualTo(7L);
            assertThat(event.labOrderIds()).containsExactly(11L);
            assertThat(event.medicationOrderIds()).containsExactly(12L);
            // EXAM 由 dispatch 模块按预约生成检查任务,不属于这个事件的职责
            assertThat(event.labOrderIds()).doesNotContain(13L);
        }

        @Test
        @DisplayName("确单时没有任何待执行医嘱 → 不发事件(避免空事件污染下游日志)")
        void confirm_noCreatedOrders_noEvent() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));
            when(orderMapper.selectList(any())).thenReturn(List.of());
            when(chargeMapper.selectList(any())).thenReturn(List.of());

            service.confirm(1L);

            verify(eventPublisher, org.mockito.Mockito.never())
                    .publishEvent(any(VisitOrdersConfirmedEvent.class));
        }

        @Test
        @DisplayName("重复确单(已 CONFIRMED 提前返回)→ 不再发事件,下游不会被重复触发")
        void confirm_alreadyConfirmed_noEvent() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CONFIRMED"));

            service.confirm(1L);

            verify(eventPublisher, org.mockito.Mockito.never())
                    .publishEvent(any(VisitOrdersConfirmedEvent.class));
        }
    }

    @Nested
    @DisplayName("触发点二:已确单后追加医嘱 addOrder")
    class AddOrder {

        @Test
        @DisplayName("就诊 CONFIRMED 时追加检验医嘱 → 单条入检验桶(替原先前端再发一次的编排)")
        void addOrder_onConfirmedVisit_publishesForNewOrder() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CONFIRMED"));
            when(patientService.get(any())).thenReturn(null);
            // 模拟自增主键回填
            org.mockito.Mockito.doAnswer(inv -> {
                inv.getArgument(0, Order.class).setId(99L);
                return 1;
            }).when(orderMapper).insert(any(Order.class));

            service.addOrder(1L, order(null, "LAB"));

            VisitOrdersConfirmedEvent event = captureDownstreamEvent();
            assertThat(event).isNotNull();
            assertThat(event.labOrderIds()).containsExactly(99L);
            assertThat(event.medicationOrderIds()).isEmpty();
        }

        @Test
        @DisplayName("就诊 IN_PROGRESS(已结算)时追加药品医嘱 → 单条入药品桶")
        void addOrder_onInProgressVisit_publishesMedicationBucket() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "IN_PROGRESS"));
            when(patientService.get(any())).thenReturn(null);
            org.mockito.Mockito.doAnswer(inv -> {
                inv.getArgument(0, Order.class).setId(88L);
                return 1;
            }).when(orderMapper).insert(any(Order.class));

            service.addOrder(1L, order(null, "MEDICATION"));

            VisitOrdersConfirmedEvent event = captureDownstreamEvent();
            assertThat(event).isNotNull();
            assertThat(event.medicationOrderIds()).containsExactly(88L);
            assertThat(event.labOrderIds()).isEmpty();
        }

        @Test
        @DisplayName("草稿态(CREATED)追加医嘱 → 不发事件(草稿不生成下游单据,等确单时批量处理)")
        void addOrder_onDraftVisit_noEvent() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CREATED"));
            when(patientService.get(any())).thenReturn(null);
            org.mockito.Mockito.doAnswer(inv -> {
                inv.getArgument(0, Order.class).setId(77L);
                return 1;
            }).when(orderMapper).insert(any(Order.class));

            service.addOrder(1L, order(null, "LAB"));

            verify(eventPublisher, org.mockito.Mockito.never())
                    .publishEvent(any(VisitOrdersConfirmedEvent.class));
        }

        @Test
        @DisplayName("追加 EXAM 医嘱 → 不发事件(检查任务由 dispatch 负责,不是本事件职责)")
        void addOrder_examOrder_noEvent() {
            when(visitMapper.selectById(1L)).thenReturn(visit(1L, "CONFIRMED"));
            when(patientService.get(any())).thenReturn(null);
            org.mockito.Mockito.doAnswer(inv -> {
                inv.getArgument(0, Order.class).setId(66L);
                return 1;
            }).when(orderMapper).insert(any(Order.class));

            service.addOrder(1L, order(null, "EXAM"));

            verify(eventPublisher, org.mockito.Mockito.never())
                    .publishEvent(any(VisitOrdersConfirmedEvent.class));
        }
    }
}
