package com.hospital.core.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R-46: 防超卖的<b>数据库级真并发</b>用例。
 *
 * <p>{@link BookingServiceTest} 里所谓"并发"只是 Mockito 顺序返回(第一个 1、第二个 0),
 * 并未真正让多个线程同时竞争同一行。本用例用真实 PostgreSQL + 8 个线程同时调用
 * {@link BookingService#book},验证 {@code SlotMapper.incrementBooked}({@code WHERE booked < capacity})
 * 的行锁在真并发下确实只放行 1 个事务。
 *
 * <p><b>刻意不加 {@code @Transactional}</b>:若加了,所有线程会共享同一连接/事务,
 * 根本测不出并发(且会绕过行锁的可见性)。清理改在 {@link #cleanup()} 里按用例自造的
 * packageId 前缀删除,避免污染演示库。
 */
@SpringBootTest
class BookingConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired BookingService bookingService;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 本次用例专属标记,便于定位/清理(不依赖它清理,清理按 packageId 精确删除)。 */
    private final String tag = "r46-concurrency-" + System.nanoTime();

    private Long packageId;
    private Long slotId;

    @BeforeEach
    void prepare() {
        packageId = jdbcTemplate.queryForObject(
                "INSERT INTO booking.exam_package (name, price, description) VALUES (?, ?, ?) RETURNING id",
                Long.class, tag, new BigDecimal("100.00"), "R-46 concurrency fixture");

        // capacity=1 的号源。刻意不插 exam_item → AppointmentCreatedEvent.items() 为空,
        // 下游 dispatch 的 @TransactionalEventListener 不会落 exam_task / queue_board,避免无关脏数据。
        LocalDate examDate = LocalDate.now().plusDays(1);
        slotId = jdbcTemplate.queryForObject(
                "INSERT INTO booking.slot (package_id, exam_date, period, capacity, booked) "
                        + "VALUES (?, ?, 'AM', 1, 0) RETURNING id",
                Long.class, packageId, examDate);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM booking.appointment WHERE package_id = ?", packageId);
        jdbcTemplate.update("DELETE FROM booking.slot WHERE package_id = ?", packageId);
        jdbcTemplate.update("DELETE FROM booking.exam_package WHERE id = ?", packageId);
    }

    @Test
    @DisplayName("R-46 真并发:8 线程抢 capacity=1 号源 → 恰好 1 成功,booked=1,BOOKED 预约=1")
    void concurrentBook_exactlyOneSucceeds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger slotFull = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(THREADS);

        try {
            for (int i = 0; i < THREADS; i++) {
                long patientId = 8_000_000L + i;   // 各不相同,排除"同患者重复预约"幂等拦截的干扰
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();               // 所有线程齐备后再同一瞬间放行,制造真实竞争
                    try {
                        bookingService.book(patientId, packageId, slotId);
                        success.incrementAndGet();
                    } catch (IllegalStateException e) {
                        // 唯一预期失败:号源已满(DB 行锁下 incrementBooked 返回 0)
                        if (e.getMessage() != null && e.getMessage().contains("号源已满")) {
                            slotFull.incrementAndGet();
                        } else {
                            throw e;             // 其它异常视为真实缺陷,交给 Future.get 抛出
                        }
                    }
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("全部线程应就绪").isTrue();
            start.countDown();

            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);     // 任一线程异常 / 超时都会在此显式失败
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(success.get()).as("并发下应恰好 1 个线程占号成功").isEqualTo(1);
        assertThat(slotFull.get()).as("其余线程应因号源已满而失败").isEqualTo(THREADS - 1);

        Integer booked = jdbcTemplate.queryForObject(
                "SELECT booked FROM booking.slot WHERE id = ?", Integer.class, slotId);
        assertThat(booked).as("号源 booked 必须恰为 1,不得超卖").isEqualTo(1);

        Integer bookedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking.appointment WHERE slot_id = ? AND status = 'BOOKED'",
                Integer.class, slotId);
        assertThat(bookedRows).as("该号源应只有 1 条 BOOKED 预约").isEqualTo(1);
    }
}
