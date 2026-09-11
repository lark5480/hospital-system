package com.hospital.core.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.hospital.core.booking.domain.Appointment;
import com.hospital.core.booking.domain.AppointmentCancelledEvent;
import com.hospital.core.booking.infrastructure.AppointmentMapper;
import com.hospital.core.booking.infrastructure.ExamItemMapper;
import com.hospital.core.booking.infrastructure.ExamPackageMapper;
import com.hospital.core.booking.infrastructure.SlotMapper;
import com.hospital.core.patient.api.PatientApi;

/**
 * {@link BookingService#cancelAppointment(Long)} 的纯 Mockito 单测(不启 Spring)。
 *
 * <p>覆盖「取消」这条写路径的全部关键断言:
 * <ul>
 *   <li>状态机:只有 BOOKED 可取消,CHECKED_IN / DONE / CANCELLED 一律 409 拒绝;</li>
 *   <li>副作用:成功时置 CANCELLED + 释放号源(releaseBookedBatch)+ 发布 AppointmentCancelledEvent;</li>
 *   <li>拒绝时零副作用:不释放号源、不发事件、不改库;</li>
 *   <li>预约不存在 → IllegalArgumentException(全局异常处理器映射 404)。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceCancelTest {

    @Mock ExamPackageMapper packageMapper;
    @Mock ExamItemMapper itemMapper;
    @Mock SlotMapper slotMapper;
    @Mock AppointmentMapper appointmentMapper;
    @Mock PatientApi patientApi;
    @Mock ApplicationEventPublisher publisher;

    @Captor ArgumentCaptor<Appointment> appointmentCaptor;
    @Captor ArgumentCaptor<AppointmentCancelledEvent> eventCaptor;
    /** 捕获批量释放号源的入参,断言 slotId 与 cnt。 */
    @Captor ArgumentCaptor<List<Map<String, Object>>> releaseCaptor;

    BookingService service;

    private static final Long APPT_ID = 100L;
    private static final Long PATIENT_ID = 42L;
    private static final Long SLOT_ID = 7L;

    @BeforeEach
    void setUp() {
        service = new BookingService(packageMapper, itemMapper, slotMapper,
                appointmentMapper, patientApi, publisher);
    }

    private static Appointment appt(String status, Long slotId) {
        Appointment a = new Appointment();
        a.setId(APPT_ID);
        a.setPatientId(PATIENT_ID);
        a.setPackageId(7L);
        a.setSlotId(slotId);
        a.setStatus(status);
        return a;
    }

    @Test
    @DisplayName("BOOKED → 置 CANCELLED + 释放号源(releaseBookedBatch slotId/cnt=1)+ 发布取消事件")
    void cancel_booked_success() {
        when(appointmentMapper.selectById(APPT_ID)).thenReturn(appt("BOOKED", SLOT_ID));

        service.cancelAppointment(APPT_ID);

        // 1) 状态落库为 CANCELLED
        verify(appointmentMapper).updateById(appointmentCaptor.capture());
        assertThat(appointmentCaptor.getValue().getStatus()).isEqualTo("CANCELLED");

        // 2) 号源释放:复用 cleanupExpiredAppointments 的批量路径,单元素 {slotId, cnt=1}
        verify(slotMapper).releaseBookedBatch(releaseCaptor.capture());
        List<Map<String, Object>> decrements = releaseCaptor.getValue();
        assertThat(decrements).hasSize(1);
        assertThat(decrements.get(0).get("slotId")).isEqualTo(SLOT_ID);
        assertThat(decrements.get(0).get("cnt")).isEqualTo(1);

        // 3) 发布取消事件 → dispatch 清理排队任务与看板投影
        verify(publisher).publishEvent(eventCaptor.capture());
        AppointmentCancelledEvent event = eventCaptor.getValue();
        assertThat(event.appointmentId()).isEqualTo(APPT_ID);
        assertThat(event.patientId()).isEqualTo(PATIENT_ID);
    }

    @Test
    @DisplayName("已支付预约被取消 → 同步置 REFUNDED(避免出现\"已支付 + 已取消\"的矛盾展示)")
    void cancel_paidAppointment_marksRefunded() {
        Appointment paid = appt("BOOKED", SLOT_ID);
        paid.setPayStatus("PAID");
        paid.setPayAmount(new java.math.BigDecimal("299.00"));
        when(appointmentMapper.selectById(APPT_ID)).thenReturn(paid);

        service.cancelAppointment(APPT_ID);

        verify(appointmentMapper).updateById(appointmentCaptor.capture());
        Appointment saved = appointmentCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("CANCELLED");
        assertThat(saved.getPayStatus()).isEqualTo("REFUNDED");
        // 金额本身不变 —— 这里没有真实退款发生,只是把状态改成自洽的终态
        assertThat(saved.getPayAmount()).isEqualByComparingTo("299.00");
    }

    @Test
    @DisplayName("未支付预约被取消 → 不动付费状态(避免凭空造出 REFUNDED)")
    void cancel_unpaidAppointment_keepsPayStatus() {
        Appointment unpaid = appt("BOOKED", SLOT_ID);
        unpaid.setPayStatus("UNPAID");
        when(appointmentMapper.selectById(APPT_ID)).thenReturn(unpaid);

        service.cancelAppointment(APPT_ID);

        verify(appointmentMapper).updateById(appointmentCaptor.capture());
        assertThat(appointmentCaptor.getValue().getPayStatus()).isEqualTo("UNPAID");
    }

    @ParameterizedTest(name = "状态 {0} → 拒绝取消(409)")
    @ValueSource(strings = {"CHECKED_IN", "DONE", "CANCELLED"})
    @DisplayName("非 BOOKED 状态(已到院/已完成/已取消) → 拒绝:不释放号源、不发事件、不改库")
    void cancel_nonBooked_rejected(String status) {
        when(appointmentMapper.selectById(APPT_ID)).thenReturn(appt(status, SLOT_ID));

        assertThatThrownBy(() -> service.cancelAppointment(APPT_ID))
                .isInstanceOf(IllegalStateException.class)   // → 409
                .hasMessageContaining("取消");

        // 零副作用:重复取消不得二次释放号源(否则 booked 会被扣成负数/释放别人的名额)
        verify(slotMapper, never()).releaseBookedBatch(anyList());
        verify(publisher, never()).publishEvent(any(Appointment.class));
        verify(appointmentMapper, never()).updateById(any(Appointment.class));
    }

    @Test
    @DisplayName("预约不存在 → IllegalArgumentException(全局处理器映射 404),零副作用")
    void cancel_notFound_throws404() {
        when(appointmentMapper.selectById(APPT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.cancelAppointment(APPT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预约不存在");

        verify(slotMapper, never()).releaseBookedBatch(anyList());
        verify(publisher, never()).publishEvent(any(Appointment.class));
        verify(appointmentMapper, never()).updateById(any(Appointment.class));
    }

    @Test
    @DisplayName("slotId 为 null(历史无号源预约) → 不调 releaseBookedBatch,但仍置 CANCELLED 并发事件")
    void cancel_nullSlotId_skipsRelease() {
        when(appointmentMapper.selectById(APPT_ID)).thenReturn(appt("BOOKED", null));

        service.cancelAppointment(APPT_ID);

        // 空参数列表会拼出非法 SQL,必须跳过释放而不是传空 list
        verify(slotMapper, never()).releaseBookedBatch(anyList());
        verify(appointmentMapper).updateById(appointmentCaptor.capture());
        assertThat(appointmentCaptor.getValue().getStatus()).isEqualTo("CANCELLED");
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().appointmentId()).isEqualTo(APPT_ID);
    }
}
