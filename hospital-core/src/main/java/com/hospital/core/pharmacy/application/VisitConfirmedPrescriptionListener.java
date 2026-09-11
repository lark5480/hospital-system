package com.hospital.core.pharmacy.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hospital.core.clinical.domain.VisitOrdersConfirmedEvent;
import com.hospital.core.platform.infrastructure.AuditRecorder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 就诊确单 → 生成处方(R-64)。
 *
 * <p>与 {@code VisitConfirmedLabListener} 同源同构:取代原先前端确单后的
 * {@code POST /api/pharmacy/prescriptions} 客户端编排,把一致性责任收回服务端。
 *
 * <p>执行时机、{@code REQUIRES_NEW} 隔离、吞异常的取舍理由见
 * {@code com.hospital.core.lab.application.VisitConfirmedLabListener} 的类注释,此处不重复。
 *
 * <p>注意处方侧与检验侧的一个差异:{@code PrescriptionService#createFromVisit} 会顺带发布
 * {@code VisitStatusEvent("PRESCRIPTION_CREATED")} 提醒收费处 —— 那是"生成后通知",
 * 与本次"确单触发"是两个不同职责,不应合并。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitConfirmedPrescriptionListener {

    private final PrescriptionService prescriptionService;
    private final AuditRecorder auditRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onVisitOrdersConfirmed(VisitOrdersConfirmedEvent event) {
        // 没有药品医嘱 → 本就不该有处方,直接跳过(不视为失败)。
        // 发布方已保证两个桶不同时为空,这里是防御性判断(便于单测直接构造"只有检验桶"的事件)。
        if (event.medicationOrderIds() == null || event.medicationOrderIds().isEmpty()) {
            return;
        }
        try {
            prescriptionService.createFromVisit(event.visitId(), event.doctorId());
            // R-64: 补审计(理由见 Lab 侧类注释:生成不经 Controller,切面记不到账)。
            // 动作名沿用 POST /api/pharmacy/prescriptions 的 CREATE_PRESCRIPTION。
            // 放在 createFromVisit 之后:失败时不写,避免"审计说建了、库里没有"。
            auditRecorder.record("CREATE_PRESCRIPTION", "visit_id=" + event.visitId(),
                    "触发: " + event.triggerLabel() + "; order_ids=" + event.medicationOrderIds());
        } catch (Exception ex) {
            // 见 Lab 侧类注释:不能向上抛,否则确单接口会返回 500(而就诊其实已确单)
            log.error("[R-64] 确单后生成处方失败,已交由对账任务补建: visitId={}, medicationOrderIds={}",
                    event.visitId(), event.medicationOrderIds(), ex);
        }
    }
}
