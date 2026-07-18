package com.hospital.core.platform.job;

import com.hospital.core.booking.application.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期预约清理。
 * <p>
 * 委托 {@link BookingService#cleanupExpiredAppointments()} 执行,
 * 不直接操作 domain 实体。
 * <p>
 * CRON: {@code 0 0 4 * * ?} (每天凌晨 4 点)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentCleanupJob {

    private final BookingService bookingService;

    @Scheduled(cron = "0 0 4 * * ?", zone = "Asia/Shanghai")
    public void execute() {
        long start = System.currentTimeMillis();
        int cancelled = bookingService.cleanupExpiredAppointments();
        log.info("AppointmentCleanupJob done: cancelled={}, cost={}ms", cancelled, System.currentTimeMillis() - start);
    }
}
