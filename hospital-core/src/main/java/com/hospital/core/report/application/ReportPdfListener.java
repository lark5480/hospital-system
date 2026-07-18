package com.hospital.core.report.application;

import com.hospital.core.report.domain.ReportPdfEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 报告 PDF 生成监听器:体检全部完成、报告落库并提交事务后,异步生成 PDF。
 * - {@code @TransactionalEventListener(AFTER_COMMIT)}:确保报告已持久化再生成,避免读不到。
 * - {@code fallbackExecution = true}:若发布处无事务(如手工补生成),仍立即执行。
 * - {@code @Async}:交线程池执行,不阻塞叫号/完成主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportPdfListener {

    private final ReportPdfGenerator generator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async("reportPdfExecutor")
    public void onReportPdf(ReportPdfEvent event) {
        log.debug("[pdf] 收到生成事件: reportId={}", event.reportId());
        generator.generate(event.reportId(), event.patientId(), event.title(), event.content());
    }
}
