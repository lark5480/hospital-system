package com.hospital.core.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R-47: {@link BookingService#cleanupExpiredAppointments()} 的<b>真实 PostgreSQL</b> 语义用例。
 *
 * <p>为什么单靠 Mockito 不够:{@code cleanup()} 只负责"处理 {@code AppointmentMapper.selectExpiredBooked}
 * 选出的行",而"只挑过期 BOOKED、不误伤已完成 / 已取消 / 未来预约"这一保证落在<b>查询 SQL</b>里。
 * 因此只有打到真实库才能证明它。本用例自造:过期 BOOKED / 过期已完成 / 过期已取消 / 未来 BOOKED,
 * 断言清理后只有"过期 BOOKED"被置 CANCELLED 且号源归还,其余数据纹丝不动。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>(同 {@code BookingConcurrencyTest}):清理是独立事务提交的,
 * 若套在测试事务里则无法验证真实落库;改在 {@link #cleanup()} 里按本次自造的 packageId 精确删除。
 */
@SpringBootTest
class BookingCleanupExpiredIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 本次用例专属标记(套餐名),便于定位;清理按 packageId 精确删除,不依赖它。 */
    private final String tag = "r47-cleanup-" + System.nanoTime();
    private Long packageId;

    @BeforeEach
    void prepare() {
        packageId = jdbcTemplate.queryForObject(
                "INSERT INTO booking.exam_package (name, price, description) VALUES (?, ?, ?) RETURNING id",
                Long.class, tag, new BigDecimal("100.00"), "R-47 cleanup fixture");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM booking.appointment WHERE package_id = ?", packageId);
        jdbcTemplate.update("DELETE FROM booking.slot WHERE package_id = ?", packageId);
        jdbcTemplate.update("DELETE FROM booking.exam_package WHERE id = ?", packageId);
    }

    private Long newSlot(LocalDate date, String period, int capacity, int booked) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO booking.slot (package_id, exam_date, period, capacity, booked) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, packageId, date, period, capacity, booked);
    }

    private Long newAppt(Long slotId, String status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO booking.appointment (patient_id, package_id, slot_id, status) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, 8_100_000L, packageId, slotId, status);
    }

    private String apptStatus(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM booking.appointment WHERE id = ?", String.class, id);
    }

    private int slotBooked(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT booked FROM booking.slot WHERE id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("R-47 只清理过期 BOOKED:过期BOOKED→CANCELLED并归还号源;已完成/已取消/未来预约不受影响")
    void cleanup_onlyExpiredBooked() {
        LocalDate past = LocalDate.now().minusDays(3);
        LocalDate future = LocalDate.now().plusDays(3);

        Long expiredSlot = newSlot(past, "AM", 5, 2);   // booked=2,对应 2 条过期 BOOKED
        Long expiredBooked1 = newAppt(expiredSlot, "BOOKED");
        Long expiredBooked2 = newAppt(expiredSlot, "BOOKED");
        Long expiredCancelled = newAppt(expiredSlot, "CANCELLED");
        Long expiredDone = newAppt(expiredSlot, "DONE");

        Long futureSlot = newSlot(future, "AM", 5, 1);
        Long futureBooked = newAppt(futureSlot, "BOOKED");

        int n = bookingService.cleanupExpiredAppointments();

        // 本用例自造 2 条过期 BOOKED;断言至少处理到它们(演示库若另有历史过期 BOOKED 不计入本断言)
        assertThat(n).isGreaterThanOrEqualTo(2);

        // 过期 BOOKED → CANCELLED
        assertThat(apptStatus(expiredBooked1)).isEqualTo("CANCELLED");
        assertThat(apptStatus(expiredBooked2)).isEqualTo("CANCELLED");
        // 不误伤:已取消保持 CANCELLED;已完成保持 DONE(不得被改写)
        assertThat(apptStatus(expiredCancelled)).isEqualTo("CANCELLED");
        assertThat(apptStatus(expiredDone)).as("已完成状态不得被清理改写").isEqualTo("DONE");
        // 未过期(未来)预约不受影响
        assertThat(apptStatus(futureBooked)).as("未来预约不应被清理").isEqualTo("BOOKED");

        // 号源归还:过期号的 booked 2 → 0(按取消的 BOOKED 数归还);未来号 booked 保持 1
        assertThat(slotBooked(expiredSlot)).as("过期号源 booked 应按取消数归还").isZero();
        assertThat(slotBooked(futureSlot)).as("未来号源 booked 不应变化").isEqualTo(1);
    }

    @Test
    @DisplayName("R-47 清理幂等:重复执行第二次返回 0,预约状态与号源 booked 不再变化")
    void cleanup_idempotent() {
        LocalDate past = LocalDate.now().minusDays(3);
        Long slot = newSlot(past, "PM", 5, 1);
        Long appt = newAppt(slot, "BOOKED");

        assertThat(bookingService.cleanupExpiredAppointments()).isGreaterThanOrEqualTo(1);
        assertThat(apptStatus(appt)).isEqualTo("CANCELLED");
        assertThat(slotBooked(slot)).isZero();

        // 第二轮:已无过期 BOOKED,返回 0,且状态/号源不再变化
        assertThat(bookingService.cleanupExpiredAppointments()).isZero();
        assertThat(apptStatus(appt)).isEqualTo("CANCELLED");
        assertThat(slotBooked(slot)).isZero();
    }
}
