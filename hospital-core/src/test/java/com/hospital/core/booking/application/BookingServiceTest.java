package com.hospital.core.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
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

import com.hospital.core.booking.domain.Appointment;
import com.hospital.core.booking.domain.AppointmentCreatedEvent;
import com.hospital.core.booking.domain.ExamItem;
import com.hospital.core.booking.domain.Slot;
import com.hospital.core.booking.infrastructure.AppointmentMapper;
import com.hospital.core.booking.infrastructure.ExamItemMapper;
import com.hospital.core.booking.infrastructure.ExamPackageMapper;
import com.hospital.core.booking.infrastructure.SlotMapper;
import com.hospital.core.patient.api.PatientApi;

/**
 * BookingService 核心路径:号源校验 + 原子占号 + 预约创建。
 * <p>
 * 覆盖场景:
 * - 号源充足 → 占号成功 → INSERT 预约 + 发布事件
 * - 号源已满 → 占号失败 → IllegalStateException(事务回滚,零副作用)
 * - 号源不存在 / 已过期 → 预检拦截,不占号不落单
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock SlotMapper slotMapper;
    @Mock AppointmentMapper appointmentMapper;
    @Mock ExamPackageMapper packageMapper;
    @Mock ExamItemMapper itemMapper;
    @Mock PatientApi patientApi;
    @Mock ApplicationEventPublisher publisher;

    @Captor ArgumentCaptor<Appointment> appointmentCaptor;
    @Captor ArgumentCaptor<AppointmentCreatedEvent> eventCaptor;

    BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(packageMapper, itemMapper, slotMapper,
                appointmentMapper, patientApi, publisher);
    }

    @Nested
    @DisplayName("原子占号")
    class SlotOccupation {

        private final Long slotId = 1L;
        private final Long patientId = 42L;
        private final Long packageId = 7L;

        /** 默认号源:今天,未过期。 */
        private Slot futureSlot() {
            Slot s = new Slot();
            s.setId(slotId);
            s.setPackageId(packageId);
            s.setExamDate(LocalDate.now());
            s.setPeriod("AM");
            s.setCapacity(2);
            s.setBooked(0);
            return s;
        }

        @Test
        @DisplayName("号源充足 → 占号成功 → INSERT 预约 + 发布事件")
        void book_success() {
            when(slotMapper.selectById(slotId)).thenReturn(futureSlot());
            when(slotMapper.incrementBooked(slotId)).thenReturn(1);
            when(itemMapper.selectList(any())).thenReturn(List.of(
                    item("采血室", "血常规", 1),
                    item("B超室", "腹部B超", 2)));
            when(patientApi.getName(patientId)).thenReturn("张三");

            Appointment result = service.book(patientId, packageId, slotId);

            // 占号调用
            verify(slotMapper).incrementBooked(slotId);

            // INSERT 的预约字段
            verify(appointmentMapper).insert(appointmentCaptor.capture());
            Appointment saved = appointmentCaptor.getValue();
            assertThat(saved.getPatientId()).isEqualTo(patientId);
            assertThat(saved.getPackageId()).isEqualTo(packageId);
            assertThat(saved.getSlotId()).isEqualTo(slotId);
            assertThat(saved.getStatus()).isEqualTo("BOOKED");
            assertThat(saved.getCreatedAt()).isNotNull();

            // 返回值(MyBatis-Plus 自增 ID 在 mock 环境不回填,只验证业务字段)
            assertThat(result.getStatus()).isEqualTo("BOOKED");

            // 事件发布
            verify(publisher).publishEvent(eventCaptor.capture());
            AppointmentCreatedEvent event = eventCaptor.getValue();
            assertThat(event.patientId()).isEqualTo(patientId);
            assertThat(event.patientName()).isEqualTo("张三");
            assertThat(event.items()).hasSize(2);
        }

        @Test
        @DisplayName("号源已满(incrementBooked=0) → IllegalStateException → 无 INSERT 无事件")
        void book_slotFull_throws() {
            when(slotMapper.selectById(slotId)).thenReturn(futureSlot());
            when(slotMapper.incrementBooked(slotId)).thenReturn(0);

            assertThatThrownBy(() -> service.book(patientId, packageId, slotId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("号源已满");

            verify(appointmentMapper, never()).insert(isA(Appointment.class));
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("号源不存在 → IllegalArgumentException → 不占号不落单")
        void book_slotNotFound_throws() {
            when(slotMapper.selectById(slotId)).thenReturn(null);

            assertThatThrownBy(() -> service.book(patientId, packageId, slotId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("号源不存在");

            verify(slotMapper, never()).incrementBooked(any());
            verify(appointmentMapper, never()).insert(isA(Appointment.class));
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("号源已过期(exam_date < 今天) → IllegalStateException → 不占号不落单")
        void book_expiredSlot_throws() {
            Slot expired = futureSlot();
            expired.setExamDate(LocalDate.now().minusDays(1));
            when(slotMapper.selectById(slotId)).thenReturn(expired);

            assertThatThrownBy(() -> service.book(patientId, packageId, slotId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("号源已过期");

            verify(slotMapper, never()).incrementBooked(any());
            verify(appointmentMapper, never()).insert(isA(Appointment.class));
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("R-25 重复预约(同患者同号源已有 BOOKED) → IllegalStateException → 不占号不落单")
        void book_duplicate_throws() {
            when(slotMapper.selectById(slotId)).thenReturn(futureSlot());
            // 已存在一条同患者同号源的 BOOKED 预约
            when(appointmentMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.book(patientId, packageId, slotId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("您已预约该时段,请勿重复提交");

            // 幂等拦截发生在占号之前:不占号、不落单、不发事件
            verify(slotMapper, never()).incrementBooked(any());
            verify(appointmentMapper, never()).insert(isA(Appointment.class));
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("并发:第一个请求成功,第二个号源已满")
        void book_concurrent_oneSucceeds() {
            when(slotMapper.selectById(slotId)).thenReturn(futureSlot());
            when(itemMapper.selectList(any())).thenReturn(List.of(
                    item("采血室", "血常规", 1),
                    item("B超室", "腹部B超", 2)));
            when(patientApi.getName(patientId)).thenReturn("张三");
            when(slotMapper.incrementBooked(slotId))
                    .thenReturn(1)   // 第一个请求
                    .thenReturn(0);  // 第二个请求

            // 第一个请求 → 成功
            Appointment result = service.book(patientId, packageId, slotId);
            assertThat(result.getStatus()).isEqualTo("BOOKED");

            // 重置 mock 以模拟独立事务
            reset(appointmentMapper);
            // 第二个请求 → 号源已满
            assertThatThrownBy(() -> service.book(patientId, packageId, slotId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("号源已满");
            verify(appointmentMapper, never()).insert(isA(Appointment.class));
        }
    }

    private static ExamItem item(String station, String name, int orderNo) {
        var i = new ExamItem();
        i.setStation(station);
        i.setName(name);
        i.setOrderNo(orderNo);
        return i;
    }
}
