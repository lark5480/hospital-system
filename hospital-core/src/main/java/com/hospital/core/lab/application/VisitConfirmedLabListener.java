package com.hospital.core.lab.application;

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
 * 就诊确单 → 生成检验申请(R-64)。
 *
 * <p>取代原先"前端确单成功后再发一个 POST /api/lab/requisitions"的客户端编排:
 * 那个做法没有补偿,请求丢失/被拦/中途失败都会让检验申请永久缺失且用户无感知。
 *
 * <p><b>执行时机</b>:{@code AFTER_COMMIT} —— 必须等确单事务提交,否则读不到已提交的医嘱与就诊状态。
 * {@code fallbackExecution = true} 保证确单若在无事务环境被调用(如某些测试/脚本)也仍然生成。
 * {@code REQUIRES_NEW} 让生成跑在自己的事务里,与确单事务彻底解耦。
 *
 * <p><b>为什么必须吞异常</b>:监听器在确单事务<b>提交之后</b>执行,此处抛异常不会回滚确单
 * (就诊已经是 CONFIRMED),只会让确单接口返回 500 —— 用户看到"确单失败"但实际已确单,
 * 状态与提示矛盾。因此这里只记录错误,补建交给 {@code DownstreamDocReconcileJob} 对账兜底。
 *
 * <p><b>为什么天然幂等</b>:{@code ConfirmService} 对已 CONFIRMED 的就诊提前返回、不再发事件;
 * 即便事件被重复投递,{@code LabService#createFromVisit} 也已改为"无可追加医嘱则返回既有申请"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitConfirmedLabListener {

    private final LabService labService;
    private final AuditRecorder auditRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onVisitOrdersConfirmed(VisitOrdersConfirmedEvent event) {
        // 没有检验医嘱 → 本就不该有检验申请,直接跳过(不视为失败)。
        // 发布方已保证两个桶不同时为空,这里是防御性判断(便于单测直接构造"只有药品桶"的事件)。
        if (event.labOrderIds() == null || event.labOrderIds().isEmpty()) {
            return;
        }
        try {
            labService.createFromVisit(event.visitId(), event.doctorId(), event.labOrderIds());
            // R-64: 补审计。生成发生在服务端、不经过 Controller ⇒ 审计切面记不到账,
            // 若不显式补写,"谁/何时/因何生成了检验申请"会从审计轨迹里消失。
            // 动作名沿用 POST /api/lab/requisitions 的 CREATE_REQUISITION,保证审计查询口径不变;
            // actor 由 AuditRecorder 取当前安全上下文(监听器仍跑在请求线程上,能取到真实操作者)。
            // 放在 createFromVisit 之后:失败时不写,避免"审计说建了、库里没有"。
            auditRecorder.record("CREATE_REQUISITION", "visit_id=" + event.visitId(),
                    "触发: " + event.triggerLabel() + "; order_ids=" + event.labOrderIds());
        } catch (Exception ex) {
            // 见类注释:不能向上抛,否则确单接口会返回 500(而就诊其实已确单)
            log.error("[R-64] 确单后生成检验申请失败,已交由对账任务补建: visitId={}, labOrderIds={}",
                    event.visitId(), event.labOrderIds(), ex);
        }
    }
}
