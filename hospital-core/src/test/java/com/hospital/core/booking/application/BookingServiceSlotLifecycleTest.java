package com.hospital.core.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hospital.core.booking.domain.Appointment;
import com.hospital.core.booking.domain.AppointmentCreatedEvent;
import com.hospital.core.booking.domain.AppointmentStatusEvent;
import com.hospital.core.booking.domain.Slot;
import com.hospital.core.booking.infrastructure.AppointmentMapper;
import com.hospital.core.booking.infrastructure.ExamItemMapper;
import com.hospital.core.booking.infrastructure.ExamPackageMapper;
import com.hospital.core.booking.infrastructure.SlotMapper;
import com.hospital.core.patient.api.PatientApi;

/**
 * R-47: 补齐 {@code BookingService} 的"号源生成 / 过期清理 / 状态机 / 异常路径"用例。
 *
 * <p>既有 {@link BookingServiceTest} 只覆盖了 {@code book()} 的占号主路径与并发(顺序返回),
 * {@link BookingConcurrencyTest} 覆盖真并发;本类只补缺口,<b>不重复</b>已有用例,
 * 全部为纯 Mockito(JUnit5 + Mockito + AssertJ),不启 Spring 上下文。
 *
 * <p>说明:{@code BookingService} 目前<b>没有</b>患者主动"取消 / 改期"方法(状态迁移由
 * Dispatch 回写的 {@link AppointmentStatusEvent} 驱动)。故"取消/改期是否触发占号回滚"一项,
 * 以 {@code cleanupExpiredAppointments} 的号源归还 + {@link AppointmentStatusEvent} 状态机来覆盖。
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceSlotLifecycleTest {

    @Mock SlotMapper slotMapper;
    @Mock AppointmentMapper appointmentMapper;
    @Mock ExamPackageMapper packageMapper;
    @Mock ExamItemMapper itemMapper;
    @Mock PatientApi patientApi;
    @Mock ApplicationEventPublisher publisher;

    @Captor ArgumentCaptor<List<Slot>> slotListCaptor;
    @Captor ArgumentCaptor<LocalDate> dateCaptor;
    @Captor ArgumentCaptor<UpdateWrapper<Appointment>> updateCaptor;
    @Captor ArgumentCaptor<List<Map<String, Object>>> releaseCaptor;
    @Captor ArgumentCaptor<AppointmentCreatedEvent> eventCaptor;

    BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(packageMapper, itemMapper, slotMapper,
                appointmentMapper, patientApi, publisher);
    }

    // ---------------------------------------------------------------------
    // 号源生成:ensureSlotsExist
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("R-47 号源生成 ensureSlotsExist")
    class SlotGeneration {

        private final Long packageId = 7L;

        private Slot existingSlot(LocalDate date, String period) {
            Slot s = new Slot();
            s.setPackageId(packageId);
            s.setExamDate(date);
            s.setPeriod(period);
            s.setCapacity(2);
            s.setBooked(0);
            return s;
        }

        @Test
        @DisplayName("空库 days=3 capacity=2 → 一次批量插入 3 天 × 2 时段 = 6 条,日期/时段/容量/已约数正确")
        void ensureSlotsExist_empty_insertsAllDaysAndPeriods() {
            when(slotMapper.selectList(any())).thenReturn(List.of());

            service.ensureSlotsExist(packageId, 3, 2);

            verify(slotMapper).batchInsert(slotListCaptor.capture());
            List<Slot> inserted = slotListCaptor.getValue();
            assertThat(inserted).hasSize(6);
            LocalDate today = LocalDate.now();
            for (int i = 0; i < 3; i++) {
                LocalDate date = today.plusDays(i);
                assertThat(inserted)
                        .filteredOn(s -> date.equals(s.getExamDate()))
                        .as("第 %d 天应各有 AM/PM 一条", i)
                        .extracting(Slot::getPeriod)
                        .containsExactlyInAnyOrder("AM", "PM");
            }
            assertThat(inserted).allSatisfy(s -> {
                assertThat(s.getPackageId()).isEqualTo(packageId);
                assertThat(s.getCapacity()).isEqualTo(2);
                assertThat(s.getBooked()).isZero();
                assertThat(s.getExamDate()).isBetween(today, today.plusDays(2));
            });
        }

        @Test
        @DisplayName("已有部分号源(今天 AM 已存在)→ 只补缺口 5 条,不重复生成今日 AM")
        void ensureSlotsExist_partialExisting_insertsOnlyGaps() {
            LocalDate today = LocalDate.now();
            when(slotMapper.selectList(any())).thenReturn(List.of(existingSlot(today, "AM")));

            service.ensureSlotsExist(packageId, 3, 2);

            verify(slotMapper).batchInsert(slotListCaptor.capture());
            List<Slot> inserted = slotListCaptor.getValue();
            assertThat(inserted).hasSize(5);
            assertThat(inserted)
                    .as("已存在的 (今天, AM) 不得重复生成")
                    .noneMatch(s -> today.equals(s.getExamDate()) && "AM".equals(s.getPeriod()));
        }

        @Test
        @DisplayName("号源已全部存在 → 幂等,不触发任何批量插入")
        void ensureSlotsExist_allExisting_skipsInsert() {
            LocalDate today = LocalDate.now();
            List<Slot> all = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                all.add(existingSlot(today.plusDays(i), "AM"));
                all.add(existingSlot(today.plusDays(i), "PM"));
            }
            when(slotMapper.selectList(any())).thenReturn(all);

            service.ensureSlotsExist(packageId, 2, 2);

            verify(slotMapper, never()).batchInsert(any());
        }

        @Test
        @DisplayName("边界 days=0 → 不生成任何号源,不触发批量插入")
        void ensureSlotsExist_zeroDays_noInsert() {
            when(slotMapper.selectList(any())).thenReturn(List.of());

            service.ensureSlotsExist(packageId, 0, 2);

            verify(slotMapper, never()).batchInsert(any());
        }

        @Test
        @DisplayName("边界 capacity=0 → 仍按 0 容量照常生成(方法无容量数值校验/过滤)")
        void ensureSlotsExist_zeroCapacity_generatesWithZero() {
            when(slotMapper.selectList(any())).thenReturn(List.of());

            service.ensureSlotsExist(packageId, 1, 0);

            verify(slotMapper).batchInsert(slotListCaptor.capture());
            assertThat(slotListCaptor.getValue()).hasSize(2)
                    .allSatisfy(s -> assertThat(s.getCapacity()).isZero());
        }
    }

    // ---------------------------------------------------------------------
    // 过期清理:cleanupExpiredAppointments
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("R-47 过期清理 cleanupExpiredAppointments")
    class ExpiredCleanup {

        private Appointment appt(Long id, Long slotId) {
            Appointment a = new Appointment();
            a.setId(id);
            a.setSlotId(slotId);
            a.setStatus("BOOKED");
            return a;
        }

        @Test
        @DisplayName("有过期 BOOKED:单条批量 UPDATE 置 CANCELLED(仅命中过期集合),并按 slot 聚合归还号源;查询边界=今天")
        void cleanupExpired_cancelsAndReleases() {
            List<Appointment> expired = List.of(appt(1L, 10L), appt(2L, 10L), appt(3L, 20L));
            when(appointmentMapper.selectExpiredBooked(any(LocalDate.class))).thenReturn(expired);

            int n = service.cleanupExpiredAppointments();

            assertThat(n).isEqualTo(3);

            // 查询时间边界:必须以"今天"为界(过期 = exam_date < today)
            verify(appointmentMapper).selectExpiredBooked(dateCaptor.capture());
            assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());

            // 只对被选中的过期 id 做一次批量 UPDATE,且只把 status 置 CANCELLED
            // (即"只处理已过期项";未过期 / 非 BOOKED 的根本不在 id 集合里 → 不会被误伤)
            verify(appointmentMapper).update(isNull(), updateCaptor.capture());
            UpdateWrapper<Appointment> uw = updateCaptor.getValue();
            assertThat(uw.getSqlSet()).as("只改 status 列").contains("status");
            assertThat(uw.getParamNameValuePairs().values()).contains("CANCELLED");
            assertThat(uw.getSqlSegment()).as("条件是 id IN(...)").contains("id").containsIgnoringCase("in");

            // 号源归还:按 slot 聚合取消数量(10 号 slot 取消 2 个,20 号 slot 取消 1 个)
            verify(slotMapper).releaseBookedBatch(releaseCaptor.capture());
            Map<Long, Integer> released = releaseCaptor.getValue().stream()
                    .collect(Collectors.toMap(m -> (Long) m.get("slotId"), m -> (Integer) m.get("cnt")));
            assertThat(released).containsOnly(entry(10L, 2), entry(20L, 1));
        }

        @Test
        @DisplayName("无过期预约 → 不 UPDATE、不释放号源,返回 0")
        void cleanupExpired_nothingExpired_noSideEffects() {
            when(appointmentMapper.selectExpiredBooked(any(LocalDate.class))).thenReturn(List.of());

            int n = service.cleanupExpiredAppointments();

            assertThat(n).isZero();
            verify(appointmentMapper, never()).update(any(), any());
            verify(slotMapper, never()).releaseBookedBatch(any());
        }

        @Test
        @DisplayName("重复执行幂等:首轮处理后已无过期 BOOKED,第二轮返回 0 且不再产生写操作")
        void cleanupExpired_idempotentSecondRun() {
            // 首轮查询命中 1 条,处理后被置 CANCELLED;第二轮查询(仍只挑 BOOKED)不再命中
            when(appointmentMapper.selectExpiredBooked(any(LocalDate.class)))
                    .thenReturn(List.of(appt(1L, 10L)))
                    .thenReturn(List.of());

            assertThat(service.cleanupExpiredAppointments()).as("首轮处理 1 条").isEqualTo(1);
            assertThat(service.cleanupExpiredAppointments()).as("二轮已无过期 BOOKED").isZero();

            // 两轮合计只有首轮发生了一次 UPDATE 与一次归还
            verify(appointmentMapper, times(1)).update(isNull(), any());
            verify(slotMapper, times(1)).releaseBookedBatch(any());
        }
    }

    // ---------------------------------------------------------------------
    // 状态机:onAppointmentStatus(由 Dispatch 回写事件驱动)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("R-47 预约状态机 onAppointmentStatus")
    class StateMachine {

        private Appointment appt(Long id, String status) {
            Appointment a = new Appointment();
            a.setId(id);
            a.setSlotId(1L);
            a.setStatus(status);
            return a;
        }

        @Test
        @DisplayName("合法:BOOKED + CHECKED_IN 事件 → 推进为 CHECKED_IN 并落库")
        void checkedIn_fromBooked_advances() {
            Appointment a = appt(1L, "BOOKED");
            when(appointmentMapper.selectById(1L)).thenReturn(a);

            service.onAppointmentStatus(new AppointmentStatusEvent(1L, "CHECKED_IN"));

            assertThat(a.getStatus()).isEqualTo("CHECKED_IN");
            verify(appointmentMapper).updateById(a);
        }

        @Test
        @DisplayName("合法:非终态 + DONE 事件 → 直接置终态 DONE")
        void done_setsTerminal() {
            Appointment a = appt(1L, "CHECKED_IN");
            when(appointmentMapper.selectById(1L)).thenReturn(a);

            service.onAppointmentStatus(new AppointmentStatusEvent(1L, "DONE"));

            assertThat(a.getStatus()).isEqualTo("DONE");
            verify(appointmentMapper).updateById(a);
        }

        @Test
        @DisplayName("非法/防回退:DONE + CHECKED_IN 事件 → 已完成的不得退回 CHECKED_IN,不落库")
        void checkedIn_fromDone_noRegression() {
            Appointment a = appt(1L, "DONE");
            when(appointmentMapper.selectById(1L)).thenReturn(a);

            service.onAppointmentStatus(new AppointmentStatusEvent(1L, "CHECKED_IN"));

            assertThat(a.getStatus()).isEqualTo("DONE");
            verify(appointmentMapper, never()).updateById(any(Appointment.class));
        }

        @Test
        @DisplayName("非法/防回退:CANCELLED + CHECKED_IN 事件 → 已取消的不得被改写,不落库")
        void checkedIn_fromCancelled_noChange() {
            Appointment a = appt(1L, "CANCELLED");
            when(appointmentMapper.selectById(1L)).thenReturn(a);

            service.onAppointmentStatus(new AppointmentStatusEvent(1L, "CHECKED_IN"));

            assertThat(a.getStatus()).isEqualTo("CANCELLED");
            verify(appointmentMapper, never()).updateById(any(Appointment.class));
        }

        @Test
        @DisplayName("幂等:CHECKED_IN + CHECKED_IN 事件 → 不重复写")
        void checkedIn_idempotent() {
            Appointment a = appt(1L, "CHECKED_IN");
            when(appointmentMapper.selectById(1L)).thenReturn(a);

            service.onAppointmentStatus(new AppointmentStatusEvent(1L, "CHECKED_IN"));

            assertThat(a.getStatus()).isEqualTo("CHECKED_IN");
            verify(appointmentMapper, never()).updateById(any(Appointment.class));
        }

        @Test
        @DisplayName("预约不存在 → 安全 no-op,不抛异常、不落库")
        void appointmentNotFound_noOp() {
            when(appointmentMapper.selectById(9L)).thenReturn(null);

            service.onAppointmentStatus(new AppointmentStatusEvent(9L, "CHECKED_IN"));

            verify(appointmentMapper, never()).updateById(any(Appointment.class));
        }
    }

    // ---------------------------------------------------------------------
    // 异常路径:book()
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("R-47 异常/边界路径 book()")
    class BookPaths {

        /** 今天、有余量的号源。 */
        private Slot futureSlot() {
            Slot s = new Slot();
            s.setId(1L);
            s.setPackageId(7L);
            s.setExamDate(LocalDate.now());
            s.setPeriod("AM");
            s.setCapacity(2);
            s.setBooked(0);
            return s;
        }

        @Test
        @DisplayName("患者档案不存在(getName 返回 null)→ 当前实现不校验患者存在性,仍预约成功且事件 patientName=null")
        void book_unknownPatient_stillBooks() {
            when(slotMapper.selectById(1L)).thenReturn(futureSlot());
            when(slotMapper.incrementBooked(1L)).thenReturn(1);
            when(itemMapper.selectList(any())).thenReturn(List.of());
            when(patientApi.getName(42L)).thenReturn(null);      // 患者不存在
            when(packageMapper.selectById(7L)).thenReturn(null);  // 套餐也不存在

            Appointment result = service.book(42L, 7L, 1L);

            // 记录当前行为:不抛异常、照常占号落单(患者存在性校验属缺口,见 R-47 汇报)
            assertThat(result.getStatus()).isEqualTo("BOOKED");
            verify(slotMapper).incrementBooked(1L);
            verify(appointmentMapper).insert(any(Appointment.class));
            verify(publisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().patientId()).isEqualTo(42L);
            assertThat(eventCaptor.getValue().patientName()).isNull();
        }

        @Test
        @DisplayName("套餐不存在 → 不抛异常,预约落库但不置付费字段(payStatus/payAmount 保持未赋值)")
        void book_missingPackage_noPayFields() {
            when(slotMapper.selectById(1L)).thenReturn(futureSlot());
            when(slotMapper.incrementBooked(1L)).thenReturn(1);
            when(itemMapper.selectList(any())).thenReturn(List.of());
            when(patientApi.getName(42L)).thenReturn("张三");
            when(packageMapper.selectById(7L)).thenReturn(null);

            service.book(42L, 7L, 1L);

            ArgumentCaptor<Appointment> apptCaptor = ArgumentCaptor.forClass(Appointment.class);
            verify(appointmentMapper).insert(apptCaptor.capture());
            Appointment saved = apptCaptor.getValue();
            assertThat(saved.getPayStatus()).isNull();
            assertThat(saved.getPayAmount()).isNull();
        }
    }
}