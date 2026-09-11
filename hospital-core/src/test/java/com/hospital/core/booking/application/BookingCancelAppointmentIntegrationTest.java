package com.hospital.core.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hospital.core.booking.domain.Appointment;

/**
 * 取消预约的<b>真实 PostgreSQL</b> 端到端用例。
 *
 * <p>为什么必须有这一条:Mockito 只能证明"service 调了 releaseBookedBatch 这个 mock",
 * 证明不了"号源真的被释放、且释放出来的名额能被别人重新占上"——那才是"取消"这个功能的全部价值。
 * 本用例打到真实库,串起完整链路:
 * <ol>
 *   <li>预约(book) → 号源 booked +1,dispatch 侧生成 ExamTask + queue_board 投影;</li>
 *   <li>取消(cancelAppointment) → 预约置 CANCELLED、号源 booked -1、
 *       dispatch 侧 PENDING 任务与看板投影一并清空(由 AppointmentCancelledEvent 驱动);</li>
 *   <li>释放出的号源可被另一位患者重新预约(证明名额真的回到了池子里,而不是被"僵尸预约"占着)。</li>
 * </ol>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>(同 {@code BookingConcurrencyTest} 与
 * {@code BookingCleanupExpiredIntegrationTest}):取消要触发 {@code @TransactionalEventListener},
 * 套在测试事务里事件不会在提交后触发,测不出 dispatch 联动;清理改在 {@link #cleanup()} 里
 * 按本次自造的 packageId 精确删除。
 */
@SpringBootTest
class BookingCancelAppointmentIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired JdbcTemplate jdbcTemplate;

    private final String tag = "cancel-appt-" + System.nanoTime();
    private Long packageId;
    private Long slotId;

    /** 用不同 patientId 区分两次预约,绕开"同患者同号源重复预约"的幂等拦截。 */
    private static final long PATIENT_A = 8_200_001L;
    private static final long PATIENT_B = 8_200_002L;

    @BeforeEach
    void prepare() {
        packageId = jdbcTemplate.queryForObject(
                "INSERT INTO booking.exam_package (name, price, description) VALUES (?, ?, ?) RETURNING id",
                Long.class, tag, new BigDecimal("100.00"), "cancel appointment fixture");

        // 两个项目 → 预约后 dispatch 会生成 2 条 ExamTask + 2 条 queue_board
        jdbcTemplate.update(
                "INSERT INTO booking.exam_item (package_id, name, station, order_no, duration_min) "
                        + "VALUES (?, ?, ?, ?, ?)", packageId, "血常规", "采血室", 1, 10);
        jdbcTemplate.update(
                "INSERT INTO booking.exam_item (package_id, name, station, order_no, duration_min) "
                        + "VALUES (?, ?, ?, ?, ?)", packageId, "腹部B超", "B超室", 2, 15);

        // capacity=1:取消后释放的这 1 个名额必须能被重新占上
        slotId = jdbcTemplate.queryForObject(
                "INSERT INTO booking.slot (package_id, exam_date, period, capacity, booked) "
                        + "VALUES (?, ?, 'AM', 1, 0) RETURNING id",
                Long.class, packageId, LocalDate.now().plusDays(1));
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM dispatch.queue_board WHERE id IN "
                + "(SELECT id FROM dispatch.exam_task WHERE appointment_id IN "
                + "(SELECT id FROM booking.appointment WHERE package_id = ?))", packageId);
        jdbcTemplate.update("DELETE FROM dispatch.exam_task WHERE appointment_id IN "
                + "(SELECT id FROM booking.appointment WHERE package_id = ?)", packageId);
        jdbcTemplate.update("DELETE FROM booking.appointment WHERE package_id = ?", packageId);
        jdbcTemplate.update("DELETE FROM booking.exam_item WHERE package_id = ?", packageId);
        jdbcTemplate.update("DELETE FROM booking.slot WHERE package_id = ?", packageId);
        jdbcTemplate.update("DELETE FROM booking.exam_package WHERE id = ?", packageId);
    }

    private int slotBooked() {
        return jdbcTemplate.queryForObject(
                "SELECT booked FROM booking.slot WHERE id = ?", Integer.class, slotId);
    }

    /** 统计本次用例自造数据产生的行数(按 packageId 精确圈定,避免污染演示库统计)。 */
    private int countRows(String sql) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, packageId);
        return n == null ? 0 : n;
    }

    private int taskCount() {
        return countRows("SELECT count(*) FROM dispatch.exam_task t "
                + "JOIN booking.appointment a ON a.id = t.appointment_id WHERE a.package_id = ?");
    }

    private int boardCount() {
        return countRows("SELECT count(*) FROM dispatch.queue_board b "
                + "JOIN dispatch.exam_task t ON t.id = b.id "
                + "JOIN booking.appointment a ON a.id = t.appointment_id WHERE a.package_id = ?");
    }

    private String apptStatus(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM booking.appointment WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("预约 → 取消:预约置 CANCELLED、号源 booked 归还 1、dispatch 任务与看板投影一并清空")
    void cancel_releasesSlotAndCleansDispatch() {
        Appointment appt = bookingService.book(PATIENT_A, packageId, slotId);

        // 前置:占号成功,dispatch 已生成 2 条排队任务 + 2 条看板投影
        assertThat(slotBooked()).isEqualTo(1);
        assertThat(taskCount()).as("预约后应生成 2 条 ExamTask").isEqualTo(2);
        assertThat(boardCount()).as("看板投影应与任务 1:1").isEqualTo(2);

        bookingService.cancelAppointment(appt.getId());

        // 1) 预约状态
        assertThat(apptStatus(appt.getId())).isEqualTo("CANCELLED");
        // 2) 号源释放:1 → 0(这是"取消"的核心价值)
        assertThat(slotBooked()).as("取消后号源 booked 必须减 1").isZero();
        // 3) dispatch 联动:未开始的排队任务与看板投影必须一并清掉,
        //    否则患者会继续挂在各科室队列里、大屏照样显示他
        assertThat(taskCount()).as("取消后 PENDING 任务必须清空").isZero();
        assertThat(boardCount()).as("取消后看板投影必须同步清空").isZero();
    }

    @Test
    @DisplayName("取消释放出的号源可被他人重新占用:capacity=1 号源取消后,第二位患者预约成功")
    void cancel_freesSlotForRebooking() {
        Appointment first = bookingService.book(PATIENT_A, packageId, slotId);
        assertThat(slotBooked()).isEqualTo(1);

        // 号源已满:第二位患者此刻必然失败
        assertThatThrownBy(() -> bookingService.book(PATIENT_B, packageId, slotId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("号源已满");

        bookingService.cancelAppointment(first.getId());

        // 释放后重新预约成功 —— 证明名额真的回到池子,而不是被已取消的预约继续占着
        Appointment second = bookingService.book(PATIENT_B, packageId, slotId);
        assertThat(second.getStatus()).isEqualTo("BOOKED");
        assertThat(slotBooked()).as("重新预约后 booked 应回到 1").isEqualTo(1);
        assertThat(taskCount()).as("新预约应重新生成 2 条排队任务").isEqualTo(2);
    }

    @Test
    @DisplayName("重复取消:第二次抛 IllegalStateException(409 语义),号源不被二次释放")
    void cancel_twice_rejected() {
        Appointment appt = bookingService.book(PATIENT_A, packageId, slotId);
        bookingService.cancelAppointment(appt.getId());
        assertThat(slotBooked()).isZero();

        assertThatThrownBy(() -> bookingService.cancelAppointment(appt.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已取消");

        // 号源不会被扣成负数,也不会多释放别人的名额
        assertThat(slotBooked()).isZero();
        assertThat(apptStatus(appt.getId())).isEqualTo("CANCELLED");
    }
}
