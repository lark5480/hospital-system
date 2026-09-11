package com.hospital.core.platform.job;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hospital.core.lab.application.LabService;
import com.hospital.core.pharmacy.application.PrescriptionService;
import com.hospital.core.platform.infrastructure.AuditRecorder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 下游单据对账兜底(R-64)。
 *
 * <p><b>为什么需要它</b>:确单后生成检验申请/处方已改为事件驱动
 * ({@code VisitConfirmedEvent} → {@code VisitConfirmedLabListener} / {@code VisitConfirmedPrescriptionListener}),
 * 监听器是 AFTER_COMMIT + REQUIRES_NEW,且<b>刻意吞掉异常</b> —— 保证下游毛病不会让医生确不了单。
 * 这个取舍的代价是"生成可能静默失败",因此<b>必须有对账安全网</b>,否则只是把失败从浏览器搬到服务端。
 *
 * <p>职责:周期扫描「就诊已确单、但仍有医嘱未被下游单据覆盖」的就诊单并补建(委托给各模块自己的
 * {@code findVisitIdsNeedingReconcile} + {@code createFromVisit},本类不直接操作实体/表)。
 * 补建全部走既有幂等路径,重复执行安全。
 *
 * <p><b>可观测性</b>:正常情况下每轮应补建 0 单(日志 DEBUG);一旦出现补建即打 WARN ——
 * 那意味着确单时的事件生成失败过,应结合上面的 ERROR 日志排查。
 *
 * <p>CRON:默认每 10 分钟({@code app.reconcile.cron} 可覆盖)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownstreamDocReconcileJob {

    private final LabService labService;
    private final PrescriptionService prescriptionService;
    private final AuditRecorder auditRecorder;

    @Scheduled(cron = "${app.reconcile.cron:0 */10 * * * ?}", zone = "Asia/Shanghai")
    public void execute() {
        long start = System.currentTimeMillis();
        int labRepaired = reconcileLabRequisitions();
        int prescriptionRepaired = reconcilePrescriptions();
        long cost = System.currentTimeMillis() - start;

        if (labRepaired + prescriptionRepaired > 0) {
            log.warn("[R-64] 下游单据对账: 补建检验申请 {} 单、处方 {} 单, cost={}ms。"
                            + "出现补建说明确单时的事件生成曾经失败,请查对应的 ERROR 日志",
                    labRepaired, prescriptionRepaired, cost);
        } else {
            log.debug("[R-64] 下游单据对账: 无缺失, cost={}ms", cost);
        }
    }

    /** 补建「已确单但仍未被申请覆盖」的检验医嘱。 */
    private int reconcileLabRequisitions() {
        List<Long> visitIds;
        try {
            visitIds = labService.findVisitIdsNeedingReconcile();
        } catch (Exception ex) {
            // 查询失败(如库不可用)不该让整轮任务崩溃,下一轮再试
            log.error("[R-64] 对账查询失败(检验侧)", ex);
            return 0;
        }
        int repaired = 0;
        for (Long visitId : visitIds) {
            try {
                // 逐单调用:createFromVisit 自带事务,单张就诊单失败不影响其它单
                labService.createFromVisit(visitId, null, null);
                repaired++;
                log.warn("[R-64] 已补建检验申请: visitId={}", visitId);
                // 与监听器写同一条动作名,靠 detail 的"触发"与 actor(system)区分是人工确单触发还是系统补建
                auditRecorder.record("CREATE_REQUISITION", "visit_id=" + visitId,
                        "触发: 对账补建(确单时生成失败或数据异常导入)");
            } catch (Exception ex) {
                log.error("[R-64] 补建检验申请失败: visitId={}", visitId, ex);
            }
        }
        return repaired;
    }

    /** 补建「已确单但仍未被处方覆盖」的药品医嘱。 */
    private int reconcilePrescriptions() {
        List<Long> visitIds;
        try {
            visitIds = prescriptionService.findVisitIdsNeedingReconcile();
        } catch (Exception ex) {
            log.error("[R-64] 对账查询失败(处方侧)", ex);
            return 0;
        }
        int repaired = 0;
        for (Long visitId : visitIds) {
            try {
                prescriptionService.createFromVisit(visitId, null);
                repaired++;
                log.warn("[R-64] 已补建处方: visitId={}", visitId);
                auditRecorder.record("CREATE_PRESCRIPTION", "visit_id=" + visitId,
                        "触发: 对账补建(确单时生成失败或数据异常导入)");
            } catch (Exception ex) {
                log.error("[R-64] 补建处方失败: visitId={}", visitId, ex);
            }
        }
        return repaired;
    }
}
