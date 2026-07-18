package com.hospital.core.platform.job;

import com.hospital.core.booking.application.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每日号源生成。
 * <p>
 * 为所有套餐生成未来 14 天的 AM/PM 号源,避免号源耗尽后新患者无法预约。
 * 委托 {@link BookingService} 执行,不直接操作 domain 实体。
 * <p>
 * CRON: {@code 0 0 3 * * ?} (每天凌晨 3 点)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotGenerateJob {

    private final BookingService bookingService;

    @Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Shanghai")
    public void execute() {
        long start = System.currentTimeMillis();
        List<Long> packageIds = bookingService.listPackageIds();
        int total = 0;

        for (Long pkgId : packageIds) {
            int before = bookingService.countSlots(pkgId);
            bookingService.ensureSlotsExist(pkgId, 14, 3);
            int after = bookingService.countSlots(pkgId);
            total += (after - before);
        }

        log.info("SlotGenerateJob done: created={}, cost={}ms", total, System.currentTimeMillis() - start);
    }
}
